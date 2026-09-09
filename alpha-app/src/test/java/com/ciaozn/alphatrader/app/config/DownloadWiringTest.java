package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.app.StubKlineExchange;
import com.ciaozn.alphatrader.app.data.JdbcKlineRepository;
import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader.DownloadResult;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader.Settings;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The download wiring (T223): what it asks the exchange for, where the answer lands, and what it
 * refuses to do. The exchange is {@link StubKlineExchange}, so none of this needs a network.
 *
 * <p>What is deliberately <em>not</em> re-tested here: paging, backoff, forming-bar rejection and
 * deduplication inside the repository. Those are the downloader's own contract and have their own
 * tests. This class is about the translation from configuration into that contract, and about the one
 * rule the download and the backtest share - the destination store.
 */
class DownloadWiringTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_704_067_200_000L;
    private static final long HOUR = 3_600_000L;
    private static final int BARS = 3;

    @TempDir
    Path directory;

    // ------------------------------------------------------------------ where the bars land

    @Test
    void theBarsLandInTheStoreTheBacktestReads() throws Exception {
        Path klines = directory.resolve("klines");
        List<Kline> served = StubKlineExchange.bars(BARS, T0, HOUR);
        try (StubKlineExchange exchange = new StubKlineExchange(served)) {
            List<DownloadResult> results = new DownloadWiring(properties(
                    download(exchange), csvStore(klines), List.of(BTC.unified()), "1h")).download();

            assertThat(results).hasSize(1);
            assertThat(results.get(0).fetched()).isEqualTo(BARS);
            assertThat(results.get(0).stored()).isEqualTo(BARS);
        }

        // The point of the whole feature: this is the repository BacktestWiring resolves from the
        // same property, and it has the bars. A download that wrote anywhere else would leave an
        // operator with a full directory and a report with no trades in it.
        CsvKlineRepository store = new CsvKlineRepository(klines);
        assertThat(store.pathFor(BTC, Interval.H1)).exists();
        assertThat(store.load(BTC, Interval.H1, T0, T0 + (BARS - 1) * HOUR)).isEqualTo(served);
    }

    @Test
    void theSameDownloadFillsTheSqliteStoreWhenThatIsWhatTheBacktestReads() throws Exception {
        Path database = directory.resolve("klines.db");
        AlphaProperties.Backtest store = new AlphaProperties.Backtest(
                new AlphaProperties.Backtest.Data("db", null, "jdbc:sqlite:" + database),
                null, null, List.of(), null, List.of(), Path.of("reports"), false, null);
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            new DownloadWiring(properties(download(exchange), store, List.of(BTC.unified()), "1h")).download();
        }

        // Both sources go through DataPlan.store, so "csv or db" is a decision about where the bars
        // live and nothing else: the download has no second code path for either.
        assertThat(database).exists();
        assertThat(new JdbcKlineRepository(dataSource("jdbc:sqlite:" + database))
                .load(BTC, Interval.H1, T0, T0 + (BARS - 1) * HOUR)).hasSize(BARS);
    }

    @Test
    void aSecondRunOverTheSameRangeStoresNothing() throws Exception {
        Path klines = directory.resolve("klines");
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            DownloadWiring wiring = new DownloadWiring(properties(
                    download(exchange), csvStore(klines), List.of(BTC.unified()), "1h"));
            assertThat(wiring.download().get(0).stored()).isEqualTo(BARS);

            // Idempotence is what makes "is my store up to date?" a question an operator can answer by
            // running the tool again rather than by inspecting a directory.
            assertThat(wiring.download().get(0).stored()).isZero();
            assertThat(new CsvKlineRepository(klines).load(BTC, Interval.H1, T0, T0 + (BARS - 1) * HOUR))
                    .hasSize(BARS);
        }
    }

    // ------------------------------------------------------------------ what it asks for

    @Test
    void theRequestNamesTheExchangeSymbolAndTheConfiguredRange() throws Exception {
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            new DownloadWiring(properties(download(exchange), csvStore(directory.resolve("klines")),
                    List.of(BTC.unified()), "1h")).download();

            assertThat(exchange.requests()).hasSize(1);
            Map<String, String> query = exchange.requests().get(0);
            // The unified name is this system's; the exchange has never heard of it. Asking for
            // BTCUSDT.PERP is how T220's fixture regeneration first came back empty.
            assertThat(query).containsEntry("symbol", "BTCUSDT")
                    .containsEntry("interval", "1h")
                    .containsEntry("startTime", Long.toString(T0))
                    .containsEntry("endTime", Long.toString(T0 + (BARS - 1) * HOUR))
                    .containsEntry("limit", Integer.toString(Settings.TESTNET.pageSize()));
        }
    }

    @Test
    void theGlobalSymbolsAndIntervalAreTheDefaultSeries() throws Exception {
        // No strategies configured at all: pulling history must not require a strategy to be valid.
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            List<DownloadResult> results = new DownloadWiring(properties(download(exchange),
                    csvStore(directory.resolve("klines")),
                    List.of(BTC.unified(), ETH.unified()), "4h")).download();

            assertThat(results).extracting(DownloadResult::symbol).containsExactly(BTC, ETH);
            assertThat(results).extracting(DownloadResult::interval).containsOnly(Interval.H4);
            assertThat(exchange.requests()).extracting(query -> query.get("symbol"))
                    .containsExactly("BTCUSDT", "ETHUSDT");
        }
    }

    @Test
    void anExplicitSeriesListReplacesTheGlobalOne() throws Exception {
        AlphaProperties.Download download = new AlphaProperties.Download(
                Instant.ofEpochMilli(T0), Instant.ofEpochMilli(T0 + (BARS - 1) * HOUR),
                List.of(new AlphaProperties.Backtest.SeriesEntry(ETH.unified(), "15m")), null);
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            AlphaProperties properties = properties(withBaseUrl(download, exchange.baseUrl()),
                    csvStore(directory.resolve("klines")), List.of(BTC.unified()), "1h");

            List<DownloadResult> results = new DownloadWiring(properties).download();

            assertThat(results).extracting(DownloadResult::symbol).containsExactly(ETH);
            assertThat(results).extracting(DownloadResult::interval).containsOnly(Interval.M15);
            // Replaced, not extended: an operator who states the series means them.
            assertThat(exchange.requests()).extracting(query -> query.get("symbol")).containsExactly("ETHUSDT");
            assertThat(new CsvKlineRepository(directory.resolve("klines")).pathFor(ETH, Interval.M15)).exists();
        }
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aMissingRangeIsRefusedBeforeAnythingIsRequested() throws Exception {
        Path klines = directory.resolve("klines");
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            AlphaProperties properties = properties(
                    new AlphaProperties.Download(null, null, List.of(), exchange.baseUrl()),
                    csvStore(klines), List.of(BTC.unified()), "1h");

            assertThatThrownBy(() -> new DownloadWiring(properties).download())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("alpha.download.from must be set");
            assertThat(exchange.requests()).isEmpty();
        }
        // Refused before touching the network or the filesystem: a run that was never going to
        // download anything should not leave a half-made store behind on the way out.
        assertThat(klines).doesNotExist();
    }

    @Test
    void aMissingEndIsRefusedAndNamesTheProperty() throws Exception {
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            AlphaProperties properties = properties(
                    new AlphaProperties.Download(Instant.ofEpochMilli(T0), null, List.of(), exchange.baseUrl()),
                    csvStore(directory.resolve("klines")), List.of(BTC.unified()), "1h");

            assertThatThrownBy(() -> new DownloadWiring(properties).download())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("alpha.download.to must be set");
            assertThat(exchange.requests()).isEmpty();
        }
    }

    @Test
    void anInvertedRangeIsRefusedRatherThanDownloadedEmpty() throws Exception {
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            AlphaProperties properties = properties(new AlphaProperties.Download(
                            Instant.ofEpochMilli(T0 + HOUR), Instant.ofEpochMilli(T0), List.of(), exchange.baseUrl()),
                    csvStore(directory.resolve("klines")), List.of(BTC.unified()), "1h");

            // The downloader refuses an inverted range too, but in epoch millis. Naming the two
            // properties is what an operator editing a yml can act on.
            assertThatThrownBy(() -> new DownloadWiring(properties).download())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("alpha.download.from must not be after alpha.download.to");
            assertThat(exchange.requests()).isEmpty();
        }
    }

    @Test
    void anUnknownStoreSourceIsRefusedNamingTheProperty() {
        AlphaProperties.Backtest store = new AlphaProperties.Backtest(
                new AlphaProperties.Backtest.Data("parquet", directory, null),
                null, null, List.of(), null, List.of(), Path.of("reports"), false, null);
        AlphaProperties.Download download = new AlphaProperties.Download(
                Instant.ofEpochMilli(T0), Instant.ofEpochMilli(T0 + HOUR), List.of(), "http://127.0.0.1:1");

        assertThatThrownBy(() -> new DownloadWiring(properties(download, store, List.of(BTC.unified()), "1h"))
                .download())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha.backtest.data.source must be 'csv' or 'db'");
    }

    // ------------------------------------------------------------------ which exchange

    @Test
    void theTestnetFlagPicksTheBaseUrlAndAnOverrideReplacesOnlyThatField() {
        assertThat(DownloadWiring.settings(true, new AlphaProperties.Download(null, null, List.of(), null)))
                .isEqualTo(Settings.TESTNET);
        assertThat(DownloadWiring.settings(false, new AlphaProperties.Download(null, null, List.of(), null)))
                .isEqualTo(Settings.LIVE);

        Settings overridden = DownloadWiring.settings(true,
                new AlphaProperties.Download(null, null, List.of(), "http://127.0.0.1:9"));
        assertThat(overridden.baseUrl()).isEqualTo("http://127.0.0.1:9");
        // Page size, request gap and retry budget are the tuned values, not a second copy of them in
        // yml: only the base URL varies, because only the base URL has to.
        assertThat(overridden.pageSize()).isEqualTo(Settings.TESTNET.pageSize());
        assertThat(overridden.requestGapMillis()).isEqualTo(Settings.TESTNET.requestGapMillis());
        assertThat(overridden.retryBackoffMillis()).isEqualTo(Settings.TESTNET.retryBackoffMillis());
        assertThat(overridden.maxAttempts()).isEqualTo(Settings.TESTNET.maxAttempts());

        // A blank override is an absent one, not a base URL of "".
        assertThat(DownloadWiring.settings(true, new AlphaProperties.Download(null, null, List.of(), "  ")))
                .isEqualTo(Settings.TESTNET);
    }

    // ------------------------------------------------------------------ leaving nothing behind

    @Test
    void noNonDaemonThreadSurvivesTheDownload() throws Exception {
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            new DownloadWiring(properties(download(exchange), csvStore(directory.resolve("klines")),
                    List.of(BTC.unified()), "1h")).download();
        }

        // The load-bearing half: the event engine's loop thread is deliberately non-daemon (the
        // online modes have to stay up between bars), and this profile has no bean to start one.
        // OkHttp's own threads are daemons, so its half cannot fail either way - what release()
        // actually leaves behind is pinned by the next test, which asks the client itself.
        assertThat(nonDaemonThreads()).noneMatch(name -> name.contains("event-engine"));
        assertThat(nonDaemonThreads()).noneMatch(name -> name.contains("OkHttp"));
    }

    @Test
    void theClientIsLeftWithNoPooledConnectionAndNoExecutor() throws Exception {
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            OkHttpClient http = new OkHttpClient();
            try (Response response = http.newCall(new Request.Builder()
                    .url(exchange.baseUrl() + "/fapi/v1/klines?symbol=BTCUSDT&interval=1h&startTime="
                            + T0 + "&endTime=" + (T0 + (BARS - 1) * HOUR) + "&limit=10")
                    .get().build()).execute()) {
                assertThat(response.code()).isEqualTo(200);
            }

            // Both are what making a request leaves behind: a keep-alive socket in the pool and an
            // executor that has been created. Asserting them first is what makes the second half
            // mean something rather than passing on a client that was never used.
            assertThat(http.connectionPool().connectionCount()).isPositive();
            assertThat(http.dispatcher().executorService().isShutdown()).isFalse();

            DownloadWiring.release(http);

            assertThat(http.connectionPool().connectionCount()).isZero();
            assertThat(http.dispatcher().executorService().isShutdown()).isTrue();
        }
    }

    @Test
    void aRefusedDownloadStillReleasesTheClient() throws Exception {
        try (StubKlineExchange exchange = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR))) {
            exchange.failWith(400);
            String baseUrl = exchange.baseUrl();

            // What is being checked is the finally: an exception on the way out must not be the path
            // that leaks the client.
            assertThatThrownBy(() -> new DownloadWiring(properties(
                    new AlphaProperties.Download(Instant.ofEpochMilli(T0),
                            Instant.ofEpochMilli(T0 + (BARS - 1) * HOUR), List.of(), baseUrl),
                    csvStore(directory.resolve("klines")), List.of(BTC.unified()), "1h")).download())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Kline request failed: HTTP 400");

            assertThat(exchange.requests()).hasSize(1);
            assertThat(nonDaemonThreads()).noneMatch(name -> name.contains("OkHttp"));
        }
    }

    // ------------------------------------------------------------------ fixture

    private static Set<String> nonDaemonThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !thread.isDaemon())
                .map(Thread::getName)
                .collect(Collectors.toSet());
    }

    private static AlphaProperties.Download download(StubKlineExchange exchange) {
        return new AlphaProperties.Download(Instant.ofEpochMilli(T0),
                Instant.ofEpochMilli(T0 + (BARS - 1) * HOUR), List.of(), exchange.baseUrl());
    }

    private static AlphaProperties.Download withBaseUrl(AlphaProperties.Download download, String baseUrl) {
        return new AlphaProperties.Download(download.from(), download.to(), download.series(), baseUrl);
    }

    private static AlphaProperties properties(AlphaProperties.Download download,
                                              AlphaProperties.Backtest backtest,
                                              List<String> symbols, String interval) {
        return new AlphaProperties("download", symbols, interval, true,
                new AlphaProperties.Trading(false), Path.of("logs"), new BigDecimal("10000"),
                null, List.of(), backtest, download);
    }

    private static AlphaProperties.Backtest csvStore(Path klines) {
        return new AlphaProperties.Backtest(
                new AlphaProperties.Backtest.Data("csv", klines, null),
                null, null, List.of(), null, List.of(), Path.of("reports"), false, null);
    }

    private static SQLiteDataSource dataSource(String url) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }
}
