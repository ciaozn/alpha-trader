package com.ciaozn.alphatrader.backtest.data;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CSV store is what a backtest actually replays, so these tests pin the two things that
 * would silently corrupt results if they drifted: exact value round-tripping (FR-BT-01) and
 * the ascending, deduplicated order the feeder's gap detection assumes (spec edge case 3).
 */
class CsvKlineRepositoryTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path directory;

    private CsvKlineRepository repository() {
        return new CsvKlineRepository(directory);
    }

    /** A bar whose prices carry a non-trivial scale, so rounding on write would be visible. */
    private static Kline bar(long openTime, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Kline(openTime, price.subtract(new BigDecimal("1.50")), price.add(new BigDecimal("2.250")),
                price.subtract(new BigDecimal("3.0500")), price, new BigDecimal("123.45600000"),
                openTime + HOUR - 1);
    }

    private static List<Long> openTimes(List<Kline> bars) {
        return bars.stream().map(Kline::openTime).toList();
    }

    @Test
    void roundTripsEveryFieldExactly() {
        CsvKlineRepository repository = repository();
        List<Kline> written = List.of(bar(T0, "36500.10"), bar(T0 + HOUR, "36750.900"));

        assertThat(repository.save(BTC, Interval.H1, written)).isEqualTo(2);
        assertThat(repository.loadAll(BTC, Interval.H1)).isEqualTo(written);
    }

    @Test
    void keepsBarsAscendingAndDeduplicatedNoMatterHowTheyArrive() {
        CsvKlineRepository repository = repository();

        repository.save(BTC, Interval.H1, List.of(bar(T0 + 2 * HOUR, "30300"), bar(T0, "30100")));
        repository.save(BTC, Interval.H1, List.of(bar(T0 + HOUR, "30200"), bar(T0, "30100")));

        assertThat(openTimes(repository.loadAll(BTC, Interval.H1)))
                .containsExactly(T0, T0 + HOUR, T0 + 2 * HOUR);
    }

    @Test
    void savingTheSameDataTwiceChangesNothing() {
        CsvKlineRepository repository = repository();
        List<Kline> bars = List.of(bar(T0, "30100"), bar(T0 + HOUR, "30200"));

        assertThat(repository.save(BTC, Interval.H1, bars)).isEqualTo(2);
        assertThat(repository.save(BTC, Interval.H1, bars)).isZero();
        assertThat(repository.loadAll(BTC, Interval.H1)).hasSize(2);
    }

    @Test
    void aCorrectedBarReplacesTheStoredOne() {
        CsvKlineRepository repository = repository();
        repository.save(BTC, Interval.H1, List.of(bar(T0, "30100"), bar(T0 + HOUR, "30200")));

        int changed = repository.save(BTC, Interval.H1, List.of(bar(T0 + HOUR, "30999")));

        assertThat(changed).isEqualTo(1);
        List<Kline> bars = repository.loadAll(BTC, Interval.H1);
        assertThat(bars).hasSize(2);
        assertThat(bars.get(1).close()).isEqualByComparingTo("30999");
    }

    @Test
    void rangeFilterIsInclusiveOnBothEnds() {
        CsvKlineRepository repository = repository();
        repository.save(BTC, Interval.H1,
                List.of(bar(T0, "30100"), bar(T0 + HOUR, "30200"), bar(T0 + 2 * HOUR, "30300")));

        assertThat(openTimes(repository.load(BTC, Interval.H1, T0 + HOUR, T0 + 2 * HOUR)))
                .containsExactly(T0 + HOUR, T0 + 2 * HOUR);
        assertThat(repository.load(BTC, Interval.H1, T0 + 3 * HOUR, T0 + 4 * HOUR)).isEmpty();
    }

    @Test
    void anUnknownSeriesLoadsEmptyInsteadOfFailing() {
        assertThat(repository().loadAll(ETH, Interval.H4)).isEmpty();
    }

    @Test
    void storesEachSeriesInItsOwnFile() {
        CsvKlineRepository repository = repository();

        repository.save(BTC, Interval.H1, List.of(bar(T0, "30100")));
        repository.save(ETH, Interval.H4, List.of(bar(T0, "2000")));
        repository.save(BTC, Interval.H4, List.of(bar(T0, "30200")));

        assertThat(repository.pathFor(BTC, Interval.H1).getFileName().toString())
                .isEqualTo("BTCUSDT.PERP-1h.csv");
        assertThat(repository.loadAll(BTC, Interval.H1)).hasSize(1);
        assertThat(repository.loadAll(BTC, Interval.H1).getFirst().close()).isEqualByComparingTo("30100");
        assertThat(repository.loadAll(ETH, Interval.H4).getFirst().close()).isEqualByComparingTo("2000");
        assertThat(repository.loadAll(BTC, Interval.H4).getFirst().close()).isEqualByComparingTo("30200");
    }

    @Test
    void theFileIsPlainReadableCsv() throws IOException {
        CsvKlineRepository repository = repository();
        repository.save(BTC, Interval.H1, List.of(bar(T0, "36500.10")));

        List<String> lines = Files.readAllLines(repository.pathFor(BTC, Interval.H1), StandardCharsets.UTF_8);

        assertThat(lines).containsExactly(CsvKlineRepository.HEADER,
                T0 + ",36498.60,36502.350,36497.0500,36500.10,123.45600000," + (T0 + HOUR - 1));
        assertThat(repository.pathFor(BTC, Interval.H1)).exists();
        // no leftover temp file: the move into place must be complete
        assertThat(Files.list(directory).map(p -> p.getFileName().toString()).toList())
                .containsExactly("BTCUSDT.PERP-1h.csv");
    }

    @Test
    void aCorruptRowFailsLoudlyInsteadOfBeingSkipped() throws IOException {
        Path path = repository().pathFor(BTC, Interval.H1);
        Files.createDirectories(directory);
        Files.writeString(path, CsvKlineRepository.HEADER + "\n" + T0 + ",oops,2,3,4,5," + (T0 + HOUR - 1) + "\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> repository().loadAll(BTC, Interval.H1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BTCUSDT.PERP-1h.csv:2")
                .hasMessageContaining("oops");
    }

    @Test
    void aRowWithTheWrongColumnCountFailsLoudly() throws IOException {
        Path path = repository().pathFor(BTC, Interval.H1);
        Files.createDirectories(directory);
        Files.writeString(path, CsvKlineRepository.HEADER + "\n" + T0 + ",1,2,3\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> repository().loadAll(BTC, Interval.H1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 7 columns, got 4");
    }

    @Test
    void savingIntoANotYetExistingDirectoryCreatesIt() {
        CsvKlineRepository repository = new CsvKlineRepository(directory.resolve("nested/data"));

        assertThat(repository.save(BTC, Interval.H1, List.of(bar(T0, "30100")))).isEqualTo(1);
        assertThat(repository.loadAll(BTC, Interval.H1)).hasSize(1);
    }

    @Test
    void rejectsABarWithANegativeOpenTime() {
        assertThatThrownBy(() -> repository().save(BTC, Interval.H1, List.of(bar(-1, "30100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openTime");
    }
}
