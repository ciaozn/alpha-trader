package com.ciaozn.alphatrader.app;

import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static com.ciaozn.alphatrader.app.BacktestSmokeTest.BARS;
import static com.ciaozn.alphatrader.app.BacktestSmokeTest.BTC;
import static com.ciaozn.alphatrader.app.BacktestSmokeTest.FROM;
import static com.ciaozn.alphatrader.app.BacktestSmokeTest.TO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regenerates {@code src/test/resources/backtest-smoke/} from the exchange and proves the committed
 * copy is reproducible from its documented provenance. It is a tool as much as a test, which is why
 * it is guarded by a system property and never runs in CI:
 * <pre>mvn -pl alpha-app -am test -Dtest=BacktestSmokeFixtureTest -Dalpha.it.testnet=true</pre>
 *
 * <p>Downloading through {@link BinanceKlineDownloader} into {@link CsvKlineRepository} rather than
 * with a script matters: those two classes are what validate the OHLC invariants, drop the bar that
 * has not closed yet, and define the on-disk format. A fixture written by anything else could be
 * shaped in a way the production path would have refused, and the smoke test would then pin numbers
 * measured on data a real run could never produce.
 *
 * <p>Klines are a public endpoint, so this needs no credentials (FR-SEC-01 stays intact).
 */
@EnabledIfSystemProperty(named = "alpha.it.testnet", matches = "true")
class BacktestSmokeFixtureTest {

    @Test
    void theCommittedDatasetIsWhatTheExchangeReturnsForThatRange() throws Exception {
        Path directory = Path.of(System.getProperty("alpha.fixture.dir",
                "src/test/resources/" + BacktestSmokeTest.DATASET_DIR));
        CsvKlineRepository repository = new CsvKlineRepository(directory);
        // OkHttp 4.x's client is not AutoCloseable, so there is nothing to close: one client, and
        // its idle threads are daemon threads that do not hold the fork open.
        BinanceKlineDownloader downloader = new BinanceKlineDownloader(new OkHttpClient(),
                BinanceKlineDownloader.Settings.TESTNET, new SystemClock());

        BinanceKlineDownloader.DownloadResult result = downloader.download(repository, BTC, Interval.H1, FROM, TO);
        System.out.printf("[fixture] %s pages=%d fetched=%d stored=%d forming=%d%n",
                BTC.unified(), result.requests(), result.fetched(), result.stored(), result.skippedForming());
        // Two pages of 1500 is the smallest pull that exercises real paging, so a downloader that
        // only ever fetched the first page could not have produced this file.
        assertThat(result.requests()).isGreaterThanOrEqualTo(2);
        assertThat(result.skippedForming()).isZero();
        assertThat(result.stored()).isEqualTo(BARS);

        List<Kline> bars = repository.load(BTC, Interval.H1, FROM, TO);
        assertThat(bars).hasSize(BARS);
        assertThat(bars.get(0).openTime()).isEqualTo(FROM);
        assertThat(bars.get(BARS - 1).openTime()).isEqualTo(TO);
        byte[] written = Files.readAllBytes(repository.pathFor(BTC, Interval.H1));
        System.out.printf("[fixture] %s: %d bytes, sha256 %s%n", repository.pathFor(BTC, Interval.H1),
                written.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(written)));

        // The point of a committed fixture is that re-fetching it changes nothing. A second
        // download reporting stored > 0 would mean the file CI compares against is not the file
        // this range produces, and every threshold in the smoke test would be unanchored.
        assertThat(downloader.download(repository, BTC, Interval.H1, FROM, TO).stored())
                .as("a second download of the same range must be a no-op")
                .isZero();
    }
}
