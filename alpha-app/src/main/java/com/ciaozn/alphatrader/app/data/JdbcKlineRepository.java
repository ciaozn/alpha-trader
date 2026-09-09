package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historical bars in the business database (FR-BT-05, DESIGN §10/§11). One table, one DDL and
 * one set of statements that run unchanged on SQLite (local dev) and MySQL 8 (server): no
 * reserved words as column names, no dialect-specific upsert, nothing that needs a migration
 * tool - the table is created on first use so the downloader cannot fail on a fresh database.
 *
 * <p>Prices and volumes are stored as text, not DECIMAL or REAL. That looks wrong and is
 * deliberate: SQLite's NUMERIC affinity would turn {@code '36498.60'} into a double and MySQL's
 * {@code DECIMAL(24,8)} would rescale it to {@code 36498.60000000}, so the bar a backtest
 * replays would no longer be the bar the exchange sent (NFR-04). No SQL here does arithmetic on
 * those columns - it only stores and ranges over them - so text costs nothing and buys exactness.
 *
 * <p>Lives in alpha-app because that is the only module allowed to carry drivers and Spring;
 * the class itself takes a plain {@link DataSource} and is unit-testable without a container.
 */
public final class JdbcKlineRepository implements KlineRepository {

    static final String TABLE = "klines";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS klines (
              symbol        VARCHAR(32) NOT NULL,
              interval_code VARCHAR(8)  NOT NULL,
              open_time     BIGINT      NOT NULL,
              open_price    VARCHAR(64) NOT NULL,
              high_price    VARCHAR(64) NOT NULL,
              low_price     VARCHAR(64) NOT NULL,
              close_price   VARCHAR(64) NOT NULL,
              volume        VARCHAR(64) NOT NULL,
              close_time    BIGINT      NOT NULL,
              PRIMARY KEY (symbol, interval_code, open_time)
            )""";

    private static final String SELECT_RANGE = """
            SELECT open_time, open_price, high_price, low_price, close_price, volume, close_time
            FROM klines
            WHERE symbol = ? AND interval_code = ? AND open_time >= ? AND open_time <= ?
            ORDER BY open_time ASC""";

    private static final String INSERT = """
            INSERT INTO klines (symbol, interval_code, open_time, open_price, high_price, low_price,
                                close_price, volume, close_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPDATE = """
            UPDATE klines
            SET open_price = ?, high_price = ?, low_price = ?, close_price = ?, volume = ?, close_time = ?
            WHERE symbol = ? AND interval_code = ? AND open_time = ?""";

    private static final Logger log = LoggerFactory.getLogger(JdbcKlineRepository.class);

    private final DataSource dataSource;

    public JdbcKlineRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        createSchema();
    }

    @Override
    public List<Kline> load(Symbol symbol, Interval interval, long fromOpenTime, long toOpenTime) {
        try (Connection connection = dataSource.getConnection()) {
            Map<Long, Kline> bars = selectRange(connection, symbol, interval, fromOpenTime, toOpenTime);
            return Collections.unmodifiableList(new ArrayList<>(bars.values()));
        } catch (SQLException e) {
            throw store("load " + symbol.unified() + "/" + interval.binanceCode(), e);
        }
    }

    @Override
    public int save(Symbol symbol, Interval interval, List<Kline> klines) {
        Map<Long, Kline> incoming = new LinkedHashMap<>();
        for (Kline kline : klines) {
            if (kline.openTime() < 0) {
                throw new IllegalArgumentException("openTime must be >= 0, got " + kline.openTime());
            }
            incoming.put(kline.openTime(), kline);
        }
        if (incoming.isEmpty()) {
            return 0;
        }

        long from = incoming.keySet().stream().mapToLong(Long::longValue).min().orElseThrow();
        long to = incoming.keySet().stream().mapToLong(Long::longValue).max().orElseThrow();

        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Map<Long, Kline> existing = selectRange(connection, symbol, interval, from, to);
                int changed = write(connection, symbol, interval, existing, incoming);
                connection.commit();
                if (changed > 0) {
                    log.debug("Stored {} new/changed bar(s) for {}/{}", changed, symbol.unified(),
                            interval.binanceCode());
                }
                return changed;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw store("save " + symbol.unified() + "/" + interval.binanceCode(), e);
        }
    }

    /**
     * Writes only what actually differs, so the returned count means the same thing on every
     * dialect - MySQL reports rows changed while SQLite reports rows matched, so neither
     * driver's update count can be trusted to answer "was this bar new?".
     */
    private int write(Connection connection, Symbol symbol, Interval interval,
                      Map<Long, Kline> existing, Map<Long, Kline> incoming) throws SQLException {
        int changed = 0;
        List<Kline> inserts = new ArrayList<>();
        List<Kline> updates = new ArrayList<>();
        for (Kline kline : incoming.values()) {
            Kline stored = existing.get(kline.openTime());
            if (stored == null) {
                inserts.add(kline);
                changed++;
            } else if (!stored.equals(kline)) {
                updates.add(kline);
                changed++;
            }
        }
        if (inserts.isEmpty() && updates.isEmpty()) {
            return 0;
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT);
             PreparedStatement update = connection.prepareStatement(UPDATE)) {
            for (Kline kline : inserts) {
                insert.setString(1, symbol.unified());
                insert.setString(2, interval.binanceCode());
                insert.setLong(3, kline.openTime());
                insert.setString(4, kline.open().toPlainString());
                insert.setString(5, kline.high().toPlainString());
                insert.setString(6, kline.low().toPlainString());
                insert.setString(7, kline.close().toPlainString());
                insert.setString(8, kline.volume().toPlainString());
                insert.setLong(9, kline.closeTime());
                insert.addBatch();
            }
            for (Kline kline : updates) {
                update.setString(1, kline.open().toPlainString());
                update.setString(2, kline.high().toPlainString());
                update.setString(3, kline.low().toPlainString());
                update.setString(4, kline.close().toPlainString());
                update.setString(5, kline.volume().toPlainString());
                update.setLong(6, kline.closeTime());
                update.setString(7, symbol.unified());
                update.setString(8, interval.binanceCode());
                update.setLong(9, kline.openTime());
                update.addBatch();
            }
            insert.executeBatch();
            update.executeBatch();
        }
        return changed;
    }

    private Map<Long, Kline> selectRange(Connection connection, Symbol symbol, Interval interval,
                                         long fromOpenTime, long toOpenTime) throws SQLException {
        Map<Long, Kline> bars = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT_RANGE)) {
            select.setString(1, symbol.unified());
            select.setString(2, interval.binanceCode());
            select.setLong(3, fromOpenTime);
            select.setLong(4, toOpenTime);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Kline kline = new Kline(rows.getLong("open_time"),
                            decimal(rows, "open_price"), decimal(rows, "high_price"),
                            decimal(rows, "low_price"), decimal(rows, "close_price"),
                            decimal(rows, "volume"), rows.getLong("close_time"));
                    bars.put(kline.openTime(), kline);
                }
            }
        }
        return bars;
    }

    private static BigDecimal decimal(ResultSet rows, String column) throws SQLException {
        String text = rows.getString(column);
        if (text == null) {
            throw new SQLException("Column " + column + " is NULL in " + TABLE);
        }
        return new BigDecimal(text);
    }

    private void createSchema() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
        } catch (SQLException e) {
            throw store("create table " + TABLE, e);
        }
    }

    private static IllegalStateException store(String what, SQLException cause) {
        return new IllegalStateException("Kline store failure while trying to " + what
                + " (sqlState " + cause.getSQLState() + ")", cause);
    }
}
