package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.Clock;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bulk historical kline download over REST (FR-BT-05): pages {@code /fapi/v1/klines} forward
 * through a time range and stores what it gets into any {@link KlineRepository}, so the same
 * tool fills a CSV directory on a laptop and a MySQL table on the server.
 *
 * <p>Two rules keep the downloaded history trustworthy:
 * <ul>
 *   <li><b>no forming bars</b> - the endpoint includes the bar that is still open, and storing
 *       it would hand the backtest a close price that never existed (FR-BT-02). Anything whose
 *       {@code closeTime} has not passed the injected {@link Clock} is dropped and counted;</li>
 *   <li><b>no silent holes</b> - the cursor advances by one interval past the last bar received,
 *       so pages cannot overlap into a duplicate nor skip forward past a bar the exchange has.
 *       Real gaps in the data are left for the feeder to detect and report (spec edge case 3).</li>
 * </ul>
 *
 * <p>Rate limits are respected rather than tested: a fixed gap between requests, and
 * exponential backoff on 429/418/5xx honouring {@code Retry-After} when the exchange sends one.
 * A 4xx that is not a rate limit fails immediately - retrying a bad symbol or range only wastes
 * the attempt budget.
 *
 * <p>Runs on whatever thread calls it (a startup task or a CLI), never the event loop: it sleeps.
 */
public final class BinanceKlineDownloader {

    public static final String TESTNET_BASE_URL = "https://testnet.binancefuture.com";
    public static final String LIVE_BASE_URL = "https://fapi.binance.com";

    /** Largest {@code limit} the endpoint accepts; larger values are rejected by Binance. */
    public static final int MAX_PAGE_SIZE = 1500;

    private static final long MAX_RETRY_BACKOFF_MILLIS = 60_000L;
    private static final String KLINES_PATH = "fapi/v1/klines";
    private static final int MAX_ERROR_BODY_CHARS = 200;

    private static final Logger log = LoggerFactory.getLogger(BinanceKlineDownloader.class);

    /**
     * @param baseUrl          REST base, testnet or live - the only difference between them
     *                         (FR-GW-05); a trailing slash is tolerated
     * @param pageSize         bars per request, at most {@link #MAX_PAGE_SIZE}
     * @param requestGapMillis polite pause between requests; 0 for tests
     * @param retryBackoffMillis first retry delay, doubling up to 60s
     * @param maxAttempts      total attempts per request, including the first
     */
    public record Settings(String baseUrl, int pageSize, long requestGapMillis,
                           long retryBackoffMillis, int maxAttempts) {

        public static final Settings TESTNET = new Settings(TESTNET_BASE_URL, MAX_PAGE_SIZE, 100L, 1000L, 5);
        public static final Settings LIVE = new Settings(LIVE_BASE_URL, MAX_PAGE_SIZE, 100L, 1000L, 5);

        public Settings {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl must not be blank");
            }
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize must be in [1, " + MAX_PAGE_SIZE + "], got " + pageSize);
            }
            if (requestGapMillis < 0 || retryBackoffMillis < 0) {
                throw new IllegalArgumentException("delays must be >= 0, got " + requestGapMillis
                        + " and " + retryBackoffMillis);
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
            }
        }
    }

    /**
     * @param requests       pages fetched - one per HTTP call that succeeded, so retries are not
     *                       counted here (they are logged)
     * @param fetched        bars the exchange returned
     * @param stored         bars the repository reported as new or changed - 0 on a re-run over
     *                       the same range, which is how a caller knows the store is up to date
     * @param skippedForming bars dropped because they had not closed yet
     */
    public record DownloadResult(Symbol symbol, Interval interval, int requests, int fetched,
                                 int stored, int skippedForming) {
    }

    private final OkHttpClient http;
    private final Settings settings;
    private final Clock clock;

    public BinanceKlineDownloader(OkHttpClient http, Settings settings, Clock clock) {
        if (http == null || settings == null || clock == null) {
            throw new IllegalArgumentException("http, settings and clock are required");
        }
        this.http = http;
        this.settings = settings;
        this.clock = clock;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Downloads {@code [startTime, endTime]} - both are bar <em>open</em> times in epoch millis,
     * both inclusive - and stores every closed bar into {@code repository}.
     */
    public DownloadResult download(KlineRepository repository, Symbol symbol, Interval interval,
                                   long startTime, long endTime) {
        if (repository == null || symbol == null || interval == null) {
            throw new IllegalArgumentException("repository, symbol and interval are required");
        }
        if (startTime < 0 || startTime > endTime) {
            throw new IllegalArgumentException(
                    "need 0 <= startTime <= endTime, got " + startTime + " and " + endTime);
        }

        long step = interval.duration().toMillis();
        // Read once: a bar that closes while the download runs is conservatively left out and
        // picked up by the next run, which is safer than storing a close that may still change.
        long now = clock.nowMillis();
        long cursor = startTime;
        int requests = 0;
        int fetched = 0;
        int stored = 0;
        int skippedForming = 0;

        while (cursor <= endTime) {
            if (requests > 0) {
                pause(settings.requestGapMillis());
            }
            List<Kline> page = fetch(symbol, interval, cursor, endTime);
            requests++;
            if (page.isEmpty()) {
                break;
            }
            fetched += page.size();

            List<Kline> closed = new ArrayList<>(page.size());
            for (Kline kline : page) {
                if (kline.closeTime() < now) {
                    closed.add(kline);
                } else {
                    skippedForming++;
                }
            }
            if (!closed.isEmpty()) {
                stored += repository.save(symbol, interval, closed);
            }

            long lastOpenTime = page.get(page.size() - 1).openTime();
            if (page.size() < settings.pageSize()) {
                break;
            }
            long next = lastOpenTime + step;
            if (next <= cursor) {
                // Unreachable with real data; guards against spinning forever on a bad clock or
                // an exchange that ignores startTime.
                log.warn("Kline cursor did not advance ({} -> {}), stopping at {} bars", cursor, next, fetched);
                break;
            }
            cursor = next;
        }

        log.info("Downloaded {} {}: {} request(s), {} bar(s) fetched, {} stored, {} still forming ({}..{})",
                symbol.unified(), interval.binanceCode(), requests, fetched, stored, skippedForming,
                startTime, endTime);
        return new DownloadResult(symbol, interval, requests, fetched, stored, skippedForming);
    }

    private List<Kline> fetch(Symbol symbol, Interval interval, long startTime, long endTime) {
        HttpUrl url = url(symbol, interval, startTime, endTime);
        BackoffPolicy backoff = new BackoffPolicy(settings.retryBackoffMillis(), MAX_RETRY_BACKOFF_MILLIS);
        for (int attempt = 1; ; attempt++) {
            try (Response response = http.newCall(new Request.Builder().url(url)
                    .header("Accept", "application/json").get().build()).execute()) {
                int code = response.code();
                ResponseBody body = response.body();
                String text = body == null ? "" : body.string();
                if (code == 200) {
                    return BinanceKlineParser.parseKlines(text);
                }
                if (attempt < settings.maxAttempts() && isRetryable(code)) {
                    long delay = retryAfterMillis(response).orElseGet(backoff::nextDelayMillis);
                    log.warn("Kline request {} returned HTTP {} (attempt {}/{}), retrying in {} ms",
                            url, code, attempt, settings.maxAttempts(), delay);
                    pause(delay);
                    continue;
                }
                throw new IllegalStateException("Kline request failed: HTTP " + code + " from " + url
                        + " - " + abbreviate(text));
            } catch (IOException e) {
                if (attempt >= settings.maxAttempts()) {
                    throw new UncheckedIOException("Kline request failed after " + attempt
                            + " attempt(s): " + url, e);
                }
                long delay = backoff.nextDelayMillis();
                log.warn("Kline request {} failed (attempt {}/{}): {} - retrying in {} ms",
                        url, attempt, settings.maxAttempts(), e.toString(), delay);
                pause(delay);
            }
        }
    }

    private HttpUrl url(Symbol symbol, Interval interval, long startTime, long endTime) {
        return HttpUrl.get(settings.baseUrl()).newBuilder()
                .addPathSegments(KLINES_PATH)
                .addQueryParameter("symbol", symbol.binance())
                .addQueryParameter("interval", interval.binanceCode())
                .addQueryParameter("startTime", Long.toString(startTime))
                .addQueryParameter("endTime", Long.toString(endTime))
                .addQueryParameter("limit", Integer.toString(settings.pageSize()))
                .build();
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
            throw new IllegalStateException("Interrupted while waiting to retry a kline request", e);
        }
    }

    private static String abbreviate(String text) {
        if (text.length() <= MAX_ERROR_BODY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }
}
