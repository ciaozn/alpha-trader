package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.gateway.binance.BinanceKlineDownloader;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real-exchange half of T212's acceptance: pull actual BTCUSDT perpetual history from
 * Binance's futures testnet and check it lands in a repository as replayable bars.
 *
 * <p>Guarded by a system property so CI and ordinary builds stay hermetic and never depend on
 * a third party being up:
 * <pre>mvn -pl alpha-gateway test -Dtest=BinanceKlineDownloaderTestnetTest -Dalpha.it.testnet=true</pre>
 *
 * <p>Needs no credentials - klines are a public endpoint (FR-SEC-01 stays intact).
 */
@EnabledIfSystemProperty(named = "alpha.it.testnet", matches = "true")
class BinanceKlineDownloaderTestnetTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long HOUR = 3_600_000L;
    /** 100 days of hourly bars is ~2400 bars, i.e. two pages - real paging, still a small pull. */
    private static final int DAYS = 100;

    @Test
    void downloadsAndStoresRealTestnetHistory() {
        OkHttpClient http = new OkHttpClient();
        try {
            SystemClock clock = new SystemClock();
            long now = clock.nowMillis();
            long from = now - DAYS * 24L * HOUR;
            InMemoryKlineRepository repository = new InMemoryKlineRepository();
            BinanceKlineDownloader downloader =
                    new BinanceKlineDownloader(http, BinanceKlineDownloader.Settings.TESTNET, clock);

            BinanceKlineDownloader.DownloadResult result =
                    downloader.download(repository, BTC, Interval.H1, from, now);

            List<Kline> bars = repository.loadAll(BTC, Interval.H1);
            System.out.printf("[testnet] %s %s pages=%d fetched=%d stored=%d forming=%d%n",
                    BTC.unified(), Interval.H1, result.requests(), result.fetched(),
                    result.stored(), result.skippedForming());
            assertThat(result.requests()).isGreaterThanOrEqualTo(2);
            assertThat(result.stored()).isEqualTo(bars.size());
            assertThat(bars).hasSizeGreaterThan(DAYS * 24 - 48);
            assertThat(bars).isSortedAccordingTo(Comparator.comparingLong(Kline::openTime));

            for (Kline kline : bars) {
                assertThat(kline.open()).isPositive();
                assertThat(kline.volume()).isNotNegative();
                assertThat(kline.high()).isGreaterThanOrEqualTo(kline.low());
                assertThat(kline.high()).isGreaterThanOrEqualTo(kline.open());
                assertThat(kline.high()).isGreaterThanOrEqualTo(kline.close());
                assertThat(kline.low()).isLessThanOrEqualTo(kline.open());
                assertThat(kline.low()).isLessThanOrEqualTo(kline.close());
                assertThat(kline.closeTime()).isEqualTo(kline.openTime() + HOUR - 1);
                // the anti-look-ahead guarantee: nothing stored that had not closed when we asked
                assertThat(kline.closeTime()).isLessThan(now);
            }

            List<Long> gaps = gapsIn(bars);
            assertThat(gaps)
                    .as("hourly testnet bars should be contiguous; a gap means the feeder would "
                            + "have to report missing data (spec edge case 3)")
                    .isEmpty();
            assertThat(result.skippedForming()).isLessThanOrEqualTo(result.requests());
        } finally {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
    }

    private static List<Long> gapsIn(List<Kline> bars) {
        List<Long> gaps = new ArrayList<>();
        for (int index = 1; index < bars.size(); index++) {
            long delta = bars.get(index).openTime() - bars.get(index - 1).openTime();
            if (delta != HOUR) {
                gaps.add(bars.get(index - 1).openTime());
            }
        }
        return gaps;
    }
}
