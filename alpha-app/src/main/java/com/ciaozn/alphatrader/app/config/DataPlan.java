package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.app.data.JdbcKlineRepository;
import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.Series;
import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.strategy.Strategy;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How {@code alpha.backtest.data} becomes a {@link KlineRepository}, and how the series to replay
 * are chosen (T219, T223).
 *
 * <p>{@link #store} has two callers and that is why it lives here rather than inside
 * {@code BacktestWiring}: the download profile writes to the very store this resolves, so a second
 * implementation of the same property would let a download fill one directory while the backtest
 * reads another. The operator would see a full CSV directory and a report with no trades, which
 * reads like a bad strategy rather than a duplicated path.
 *
 * <p>{@link #series} has one. The download deliberately does <em>not</em> use it: pulling history is
 * a data operation and must not require a strategy configuration to be valid, so it resolves from
 * the global {@code alpha.symbols} / {@code alpha.interval} instead. The two rules are allowed to
 * differ because a series with no stored bars is loud, not silent - the feeder reports it as a gap
 * covering the whole requested range (spec edge case 3).
 */
final class DataPlan {

    private DataPlan() {
    }

    /**
     * {@code csv} is one file per series under {@code csvDir}; {@code db} is the SQLite store of
     * DESIGN §10. Both satisfy the same {@code KlineRepository} contract, so nothing downstream can
     * tell them apart - which is what makes "download to CSV, replay from CSV" and "download to
     * SQLite, replay from SQLite" the same code path.
     */
    static KlineRepository store(AlphaProperties.Backtest.Data data) {
        return switch (data.source()) {
            case "csv" -> new CsvKlineRepository(data.csvDir());
            case "db" -> new JdbcKlineRepository(dataSource(data.jdbcUrl()));
            default -> throw new IllegalStateException("alpha.backtest.data.source must be 'csv' or "
                    + "'db', got '" + data.source() + "'");
        };
    }

    /**
     * SQLite, the local-dev store of DESIGN §10. No other driver is on this module's classpath, so
     * a MySQL URL is refused here with a message naming the reason instead of failing inside the
     * SQLite driver - the JDBC store's own MySQL compatibility is structural rather than tested,
     * which tasks.md records as design tradeoff 5.
     */
    private static DataSource dataSource(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("alpha.backtest.data.jdbc-url must be set when "
                    + "alpha.backtest.data.source is 'db'");
        }
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) {
            throw new IllegalStateException("alpha.backtest.data.jdbc-url must be a SQLite URL "
                    + "(jdbc:sqlite:...), got '" + jdbcUrl + "' - this module carries no other "
                    + "JDBC driver");
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(jdbcUrl);
        return dataSource;
    }

    /**
     * The explicit list wins when given; otherwise the series are derived from the strategies that
     * are actually enabled, in configuration order and deduplicated, because two strategies on
     * BTC 1h are one series. Deriving is the honest default: replaying a series no strategy listens
     * to only costs time, and omitting one a strategy needs means it silently never trades.
     *
     * <p>Derived from built {@link Strategy} instances rather than from their configuration
     * entries: a factory is free to narrow what a strategy listens to, and the instance is the
     * truth about that.
     */
    static List<Series> series(List<AlphaProperties.Backtest.SeriesEntry> configured,
                               List<Strategy> strategies) {
        Set<Series> series = new LinkedHashSet<>();
        if (configured.isEmpty()) {
            for (Strategy strategy : strategies) {
                strategy.symbols().forEach(symbol -> series.add(new Series(symbol, strategy.interval())));
            }
        } else {
            for (AlphaProperties.Backtest.SeriesEntry entry : configured) {
                series.add(new Series(Symbol.parse(entry.symbol()),
                        Interval.fromBinanceCode(entry.interval())));
            }
        }
        // List.copyOf over a LinkedHashSet keeps encounter order, which is configuration order:
        // the series order is part of what makes a replay reproducible (NFR-04).
        return List.copyOf(series);
    }
}
