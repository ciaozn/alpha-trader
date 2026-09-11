package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Startup fetch of the contract trading rules (FR-GW-03): one unsigned GET of
 * {@code /fapi/v1/exchangeInfo}, handed to {@link BinanceExchangeInfoParser}, cached in a
 * {@link FixedTradingRulesProvider}. Public endpoint, so no credentials and no time
 * synchronization are involved - which is why this takes no {@code Clock}, unlike the kline
 * downloader that needs one to recognise a bar still forming.
 *
 * <p><b>It returns the provider T201 already defined, and there is no second provider
 * implementation anywhere in the system.</b> That is what makes T209's "no cached rules = CRITICAL
 * hard stop" unbypassable by construction: {@code RiskGate} looks rules up through
 * {@code TradingRulesProvider.find}, and the class answering that call is the one its tests already
 * pin. A new caching provider would have been a new {@code find} to get subtly wrong - returning an
 * empty-but-present {@code TradingRules} for a symbol it could not parse, say, which the gate would
 * read as "rules exist" and size an order it cannot align. The parser throws in that case instead.
 *
 * <p>Snapshot, not a live view: the rules are read once at startup and never refreshed, matching
 * FR-GW-03's "拉取并缓存". An exchange that changes a symbol's step size mid-run is caught by the
 * order being rejected, which the reconciliation in T318 reports; re-fetching on a timer would add a
 * second source of truth for precision without removing that failure.
 *
 * <p>Runs on whatever thread calls it (a startup task), never the event loop: it sleeps between
 * retries. Rate limits are respected rather than tested - exponential backoff on 429/418/5xx
 * honouring {@code Retry-After} - and a 4xx that is not a rate limit fails immediately, because
 * retrying a request the exchange has already refused on its merits only spends the attempt budget.
 *
 * <p>The retry loop is deliberately a copy of the one in {@link BinanceKlineDownloader} rather than a
 * shared helper: two callers with different page/cursor concerns is not yet a pattern, and the shared
 * version would have to abstract over the part they do not have in common. When the signed-request
 * client in T316 makes it a third copy the duplication becomes real and worth extracting.
 */
public final class BinanceTradingRulesFetcher {

    public static final String TESTNET_BASE_URL = "https://testnet.binancefuture.com";
    public static final String LIVE_BASE_URL = "https://fapi.binance.com";

    private static final long MAX_RETRY_BACKOFF_MILLIS = 60_000L;
    private static final String EXCHANGE_INFO_PATH = "fapi/v1/exchangeInfo";
    private static final int MAX_ERROR_BODY_CHARS = 200;

    private static final Logger log = LoggerFactory.getLogger(BinanceTradingRulesFetcher.class);

    /**
     * @param baseUrl            REST base, testnet or live - the only difference between them
     *                           (FR-GW-05); a trailing slash is tolerated
     * @param retryBackoffMillis first retry delay, doubling up to 60s
     * @param maxAttempts        total attempts, including the first
     */
    public record Settings(String baseUrl, long retryBackoffMillis, int maxAttempts) {

        public static final Settings TESTNET = new Settings(TESTNET_BASE_URL, 1000L, 5);
        public static final Settings LIVE = new Settings(LIVE_BASE_URL, 1000L, 5);

        public Settings {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl must not be blank");
            }
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            if (retryBackoffMillis < 0) {
                throw new IllegalArgumentException("retryBackoffMillis must be >= 0, got " + retryBackoffMillis);
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
            }
        }
    }

    private final OkHttpClient http;
    private final Settings settings;

    public BinanceTradingRulesFetcher(OkHttpClient http, Settings settings) {
        if (http == null || settings == null) {
            throw new IllegalArgumentException("http and settings are required");
        }
        this.http = http;
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Fetches exchangeInfo and returns the rules for {@code symbols}, in that collection's iteration
     * order. Throws - rather than returning a provider that is missing one - if any wanted symbol is
     * absent from the payload, not trading, or carries no determinable precision.
     */
    public FixedTradingRulesProvider fetch(Collection<Symbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol is required to fetch trading rules");
        }
        List<TradingRules> rules = BinanceExchangeInfoParser.parse(get(), symbols);
        log.info("Cached trading rules for {} symbol(s) from {}", rules.size(), settings.baseUrl());
        return new FixedTradingRulesProvider(rules);
    }

    private String get() {
        HttpUrl url = HttpUrl.get(settings.baseUrl()).newBuilder()
                .addPathSegments(EXCHANGE_INFO_PATH)
                .build();
        BackoffPolicy backoff = new BackoffPolicy(settings.retryBackoffMillis(), MAX_RETRY_BACKOFF_MILLIS);
        for (int attempt = 1; ; attempt++) {
            try (Response response = http.newCall(new Request.Builder().url(url)
                    .header("Accept", "application/json").get().build()).execute()) {
                int code = response.code();
                ResponseBody body = response.body();
                String text = body == null ? "" : body.string();
                if (code == 200) {
                    return text;
                }
                if (attempt < settings.maxAttempts() && isRetryable(code)) {
                    long delay = retryAfterMillis(response).orElseGet(backoff::nextDelayMillis);
                    log.warn("exchangeInfo request returned HTTP {} (attempt {}/{}), retrying in {} ms",
                            code, attempt, settings.maxAttempts(), delay);
                    pause(delay);
                    continue;
                }
                throw new IllegalStateException("exchangeInfo request failed: HTTP " + code + " from " + url
                        + " - " + abbreviate(text));
            } catch (IOException e) {
                if (attempt >= settings.maxAttempts()) {
                    throw new UncheckedIOException("exchangeInfo request failed after " + attempt
                            + " attempt(s): " + url, e);
                }
                long delay = backoff.nextDelayMillis();
                log.warn("exchangeInfo request failed (attempt {}/{}): {} - retrying in {} ms",
                        attempt, settings.maxAttempts(), e.toString(), delay);
                pause(delay);
            }
        }
    }

    /** 429/418 are the exchange telling us to slow down; 5xx are transient. Everything else is ours. */
    private static boolean isRetryable(int code) {
        return code == 429 || code == 418 || code >= 500;
    }

    private static Optional<Long> retryAfterMillis(Response response) {
        String header = response.header("Retry-After");
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Math.max(0L, Long.parseLong(header.trim()) * 1000L));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry an exchangeInfo request", e);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= MAX_ERROR_BODY_CHARS ? text : text.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }
}
