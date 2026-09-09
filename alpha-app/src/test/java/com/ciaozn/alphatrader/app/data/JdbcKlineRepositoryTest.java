package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the store against a real SQLite file (the acceptance criterion for T211): the SQL has to
 * work on an actual database, not a mock, because the point of this class is that the same
 * statements also run on MySQL. Values are compared with {@code equals}, so scale drift - the
 * thing DECIMAL/REAL columns would introduce - fails these tests.
 */
class JdbcKlineRepositoryTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_700_000_000_000L;

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("klines.db"));
        return dataSource;
    }

    private JdbcKlineRepository repository() {
        return new JdbcKlineRepository(dataSource());
    }

    /** Prices carry different scales on purpose: a numeric column would normalize them away. */
    private static Kline bar(long openTime, String close) {
        BigDecimal price = new BigDecimal(close);
        return new Kline(openTime, price.subtract(new BigDecimal("1.50")), price.add(new BigDecimal("2.250")),
                price.subtract(new BigDecimal("3.0500")), price, new BigDecimal("123.45600000"),
                openTime + HOUR - 1);
    }

    private static List<Kline> bars(int count) {
        List<Kline> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            bars.add(bar(T0 + index * HOUR, "30000." + (10 + index)));
        }
        return bars;
    }

    private static List<Long> openTimes(List<Kline> bars) {
        return bars.stream().map(Kline::openTime).toList();
    }

    @Test
    void createsTheTableOnFirstUse() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        new JdbcKlineRepository(dataSource);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'klines'")) {
            assertThat(tables.next()).isTrue();
        }
        // constructing again over the same file must not fail: the schema step is idempotent
        assertThat(new JdbcKlineRepository(dataSource).loadAll(BTC, Interval.H1)).isEmpty();
    }

    @Test
    void roundTripsEveryFieldExactlyAcrossAReopenedStore() {
        List<Kline> written = List.of(bar(T0, "36500.10"), bar(T0 + HOUR, "36750.900"));
        assertThat(repository().save(BTC, Interval.H1, written)).isEqualTo(2);

        // a fresh instance over the same file: the data is really on disk, not in a cache
        assertThat(repository().loadAll(BTC, Interval.H1)).isEqualTo(written);
    }

    @Test
    void keepsBarsAscendingAndDeduplicatedNoMatterHowTheyArrive() {
        JdbcKlineRepository repository = repository();

        repository.save(BTC, Interval.H1, List.of(bar(T0 + 2 * HOUR, "30300"), bar(T0, "30100")));
        repository.save(BTC, Interval.H1, List.of(bar(T0 + HOUR, "30200"), bar(T0, "30100")));

        assertThat(openTimes(repository.loadAll(BTC, Interval.H1)))
                .containsExactly(T0, T0 + HOUR, T0 + 2 * HOUR);
    }

    @Test
    void savingTheSameDataTwiceChangesNothing() {
        JdbcKlineRepository repository = repository();
        List<Kline> batch = bars(3);

        assertThat(repository.save(BTC, Interval.H1, batch)).isEqualTo(3);
        assertThat(repository.save(BTC, Interval.H1, batch)).isZero();
        assertThat(repository.loadAll(BTC, Interval.H1)).hasSize(3);
    }

    @Test
    void aCorrectedBarReplacesTheStoredOne() {
        JdbcKlineRepository repository = repository();
        repository.save(BTC, Interval.H1, bars(2));

        assertThat(repository.save(BTC, Interval.H1, List.of(bar(T0 + HOUR, "30999")))).isEqualTo(1);

        List<Kline> stored = repository.loadAll(BTC, Interval.H1);
        assertThat(stored).hasSize(2);
        assertThat(stored.get(1).close()).isEqualByComparingTo("30999");
        assertThat(stored.getFirst().close()).isEqualByComparingTo("30000.10");
    }

    @Test
    void aBatchWithinOnePageIsStoredInOneGo() {
        JdbcKlineRepository repository = repository();
        List<Kline> page = bars(1500);

        assertThat(repository.save(BTC, Interval.H1, page)).isEqualTo(1500);
        assertThat(repository.loadAll(BTC, Interval.H1)).isEqualTo(page);
        assertThat(repository.save(BTC, Interval.H1, page)).isZero();
    }

    @Test
    void rangeFilterIsInclusiveOnBothEnds() {
        JdbcKlineRepository repository = repository();
        repository.save(BTC, Interval.H1, bars(3));

        assertThat(openTimes(repository.load(BTC, Interval.H1, T0 + HOUR, T0 + 2 * HOUR)))
                .containsExactly(T0 + HOUR, T0 + 2 * HOUR);
        assertThat(repository.load(BTC, Interval.H1, T0 + 3 * HOUR, T0 + 4 * HOUR)).isEmpty();
    }

    @Test
    void storesEachSeriesInItsOwnRows() {
        JdbcKlineRepository repository = repository();

        repository.save(BTC, Interval.H1, List.of(bar(T0, "30100")));
        repository.save(ETH, Interval.H4, List.of(bar(T0, "2000")));
        repository.save(BTC, Interval.H4, List.of(bar(T0, "30200")));

        assertThat(repository.loadAll(BTC, Interval.H1).getFirst().close()).isEqualByComparingTo("30100");
        assertThat(repository.loadAll(ETH, Interval.H4).getFirst().close()).isEqualByComparingTo("2000");
        assertThat(repository.loadAll(BTC, Interval.H4).getFirst().close()).isEqualByComparingTo("30200");
        assertThat(repository.loadAll(ETH, Interval.H1)).isEmpty();
    }

    @Test
    void anEmptyBatchWritesNothing() {
        JdbcKlineRepository repository = repository();

        assertThat(repository.save(BTC, Interval.H1, List.of())).isZero();
        assertThat(repository.loadAll(BTC, Interval.H1)).isEmpty();
    }

    @Test
    void rejectsABarWithANegativeOpenTime() {
        assertThatThrownBy(() -> repository().save(BTC, Interval.H1, List.of(bar(-1, "30100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openTime");
    }
}
