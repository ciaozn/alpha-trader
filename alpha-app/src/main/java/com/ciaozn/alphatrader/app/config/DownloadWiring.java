package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.Series;
import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader.DownloadResult;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader.Settings;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Download mode: pull the configured history from the exchange REST API into the store the backtest
 * reads, print what arrived, and let the process exit (T223, FR-BT-05).
 *
 * <p><b>Why this exists as a profile and not only as a class.</b> {@code BinanceKlineDownloader} was
 * already written and tested in T212, but nothing an operator could run reached it: FR-BT-05 asks
 * for a <em>tool</em>, and a tool that only a JUnit test can invoke is not one. The consequence was
 * concrete - {@code application-backtest.yml} advertises {@code csv-dir} as "what the downloader
 * writes" while nothing in the product ever writes it, and SC-01's three-year acceptance run cannot
 * be reproduced by anyone who did not also write a throwaway harness.
 *
 * <p><b>No credentials.</b> Klines are a public endpoint, so this profile needs no API key and does
 * not touch FR-SEC-01; {@code EnvValidator} only guards paper and live.
 *
 * <p><b>Why the process exits.</b> The same reason as the backtest profile: no {@code EventEngine}
 * bean and no {@link StartupWiring}, so nothing starts a non-daemon loop thread. OkHttp's own
 * threads are dealt with in {@link #release}.
 *
 * <p><b>The destination is {@code alpha.backtest.data}, not a download-specific copy.</b> The whole
 * point of downloading is that a backtest then reads what was downloaded; two knobs for one
 * directory is how an operator ends up with a full CSV directory and a report with no trades. Both
 * profiles therefore resolve the store through {@link DataPlan#store}.
 *
 * <p>Re-running over a range that is already stored is a no-op rather than a duplicate: the
 * downloader pages forward and the repository dedupes, so {@code stored} comes back 0 and the run is
 * the honest way to check whether the store is up to date.
 */
@Component
@Profile("download")
@Order(1)
public class DownloadWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DownloadWiring.class);

    private final AlphaProperties properties;

    public DownloadWiring(AlphaProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        download();
    }

    /**
     * Downloads every configured series and returns one result per series, so a caller can read the
     * counts without scraping the log.
     *
     * <p>Repeatable and idempotent: a second call over the same range fetches the same bars and
     * stores none of them.
     */
    public List<DownloadResult> download() {
        AlphaProperties.Download download = properties.download();
        // Validated before anything touches the network or the filesystem: a refused run should not
        // leave a half-written store behind on the way out.
        long from = millis(download.from(), "alpha.download.from");
        long to = millis(download.to(), "alpha.download.to");
        if (from > to) {
            throw new IllegalStateException("alpha.download.from must not be after alpha.download.to, got "
                    + download.from() + " and " + download.to());
        }
        KlineRepository repository = DataPlan.store(properties.backtest().data());
        List<Series> series = series(download);
        Settings settings = settings(properties.binanceTestnet(), download);
        log.info("Downloading {} series from {} into {} over {}..{}", series.size(), settings.baseUrl(),
                properties.backtest().data().source(), Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));

        OkHttpClient http = new OkHttpClient();
        try {
            BinanceKlineDownloader downloader =
                    new BinanceKlineDownloader(http, settings, new SystemClock());
            List<DownloadResult> results = new ArrayList<>(series.size());
            int fetched = 0;
            int stored = 0;
            for (Series each : series) {
                DownloadResult result =
                        downloader.download(repository, each.symbol(), each.interval(), from, to);
                results.add(result);
                fetched += result.fetched();
                stored += result.stored();
                log.info("Stored {} {} {}: {} request(s), {} bar(s) fetched, {} new or changed, "
                                + "{} dropped as still forming",
                        each.symbol().unified(), each.interval().binanceCode(),
                        properties.backtest().data().source(), result.requests(), result.fetched(),
                        result.stored(), result.skippedForming());
            }
            log.info("Download complete: {} bar(s) fetched, {} stored across {} series. A re-run over "
                    + "the same range stores 0, which is how to check the store is up to date.",
                    fetched, stored, results.size());
            return results;
        } finally {
            release(http);
        }
    }

    /**
     * There is no default range, and inventing one would be worse than refusing: an unbounded
     * download pages back to whatever the exchange happens to keep, which is not a stated dataset
     * and cannot be reproduced later.
     */
    private static long millis(Instant value, String property) {
        if (value == null) {
            throw new IllegalStateException(property + " must be set (ISO-8601 instant, UTC): a "
                    + "download fills a stated range, and leaving it open would page back to "
                    + "whatever the exchange keeps, which is not a dataset anyone can reproduce");
        }
        return value.toEpochMilli();
    }

    /**
     * The explicit list, or the global {@code alpha.symbols} at {@code alpha.interval}. Pulling
     * history is a data operation, so it does not require a strategy configuration to be valid -
     * and the global pair is already the system's declaration of what it watches.
     *
     * <p>A strategy enabled on a symbol outside {@code alpha.symbols} has to be listed here
     * explicitly. Forgetting it is loud rather than silent: the backtest derives its own series from
     * the enabled strategies, so the missing one comes back as a gap covering the whole range.
     */
    private List<Series> series(AlphaProperties.Download download) {
        Set<Series> series = new LinkedHashSet<>();
        if (download.series().isEmpty()) {
            Interval interval = Interval.fromBinanceCode(properties.interval());
            for (String symbol : properties.symbols()) {
                series.add(new Series(Symbol.parse(symbol), interval));
            }
        } else {
            for (AlphaProperties.Backtest.SeriesEntry entry : download.series()) {
                series.add(new Series(Symbol.parse(entry.symbol()),
                        Interval.fromBinanceCode(entry.interval())));
            }
        }
        return List.copyOf(series);
    }

    /**
     * Testnet or live - the only difference between the two (FR-GW-05). Package-private and static
     * so the mapping can be pinned without a network: which base URL an operator's bars came from is
     * part of what a dataset means.
     */
    static Settings settings(boolean binanceTestnet, AlphaProperties.Download download) {
        Settings base = binanceTestnet ? Settings.TESTNET : Settings.LIVE;
        String baseUrl = download.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return base;
        }
        // Only the base URL is configurable: page size, request gap and retry budget are the tuned
        // values in Settings, and restating them in yml would give each a second definition.
        return new Settings(baseUrl, base.pageSize(), base.requestGapMillis(),
                base.retryBackoffMillis(), base.maxAttempts());
    }

    /**
     * OkHttp 4's client is not {@code AutoCloseable}, so this is the explicit equivalent. Both
     * threads it can create are daemon threads and would not by themselves hold the JVM open, but
     * the connection pool keeps sockets and a cleanup task alive after the last response, and
     * "download and exit" should mean the process actually finished with the network.
     *
     * <p>Package-private for the same reason as {@link #settings}: the effect is on the client's
     * internals, which nothing outside can otherwise reach.
     *
     * <p>What this does is pinned by a test; that {@code download()} actually calls it is not
     * observable from outside, and that is a property of the design rather than a gap in the suite -
     * the client is created and dropped inside one method, and OkHttp's only thread is a JVM-wide
     * daemon that outlives this either way. Before deleting the {@code finally}, note that the thing
     * it prevents is a socket and a cleanup task still held after the tool has printed "complete".
     */
    static void release(OkHttpClient http) {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
