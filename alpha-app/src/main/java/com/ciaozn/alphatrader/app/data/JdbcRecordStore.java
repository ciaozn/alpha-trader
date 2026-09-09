package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Position;
import com.ciaozn.alphatrader.risk.EquitySnapshot;
import com.ciaozn.alphatrader.risk.InterceptionRecord;
import com.ciaozn.alphatrader.risk.PositionSnapshot;
import com.ciaozn.alphatrader.risk.RecordStore;
import com.ciaozn.alphatrader.risk.RiskRejection;
import com.ciaozn.alphatrader.risk.RiskRule;
import com.ciaozn.alphatrader.risk.SignalFacts;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link RecordStore} in the business database (FR-OP-04, FR-RK-08, 取舍 15). The second of the two
 * stores over one file, and the reason {@link BusinessSchema} holds all six tables rather than the two
 * {@code JdbcOrderStore} writes: whichever is constructed first creates the whole schema, so neither
 * can arrive at a database the other has half-built.
 *
 * <p><b>Every write here is a single INSERT, and there is no update path at all.</b> That is not an
 * omission but the shape of the contract - all four tables are append-only history, unlike
 * {@code orders}, which is current state rewritten on every lifecycle migration. So nothing in this
 * class can replace a row, and a fact once written means what it meant when it was written. The
 * structural consequence is that {@code JdbcOrderStore.save} branches on whether it already holds the
 * row and this one cannot.
 *
 * <p>{@code seq} is allocated the same way and for the same reason: {@code MAX(seq) + 1} read inside
 * the inserting transaction, never counted in a field, because the four reads promise insertion order
 * and SQL has none to offer. {@code business_ts} is the tempting candidate and is wrong in exactly the
 * case these tables exist to record - every signal emitted on one bar shares a timestamp, and an
 * interception arrives after the signal it refused, so a timestamp ordering can neither separate two
 * rows nor survive a fact that lands late.
 *
 * <p><b>The interception row is the expensive one: nineteen columns for two nested records.</b>
 * {@code InterceptionRecord} is a {@link SignalFacts} plus a {@link RiskRejection}, and
 * {@code SignalFacts} itself holds a {@link SignalEvent}, so reading one back rebuilds three types in
 * the opposite order. That nesting is deliberate on the way in - FR-RK-08 asks for the rule <em>and</em>
 * the account it refused against, and flattening only what seemed worth querying would leave a row
 * that names the rule and cannot be checked.
 *
 * <p>Money goes in as text and comes out as text (取舍 5), so scale survives: {@code 0.0500} is still
 * {@code 0.0500}, never {@code 0.05}. {@code strength} is written as text too, and as
 * {@code Double.toString} rather than {@code setDouble} - the column type on its own is not enough,
 * and {@link BusinessSchema} records what each of the two halves costs when it is missing.
 *
 * <p>Threading: as documented on {@link RecordStore}, written on the engine thread only. Nothing here
 * holds state between calls.
 */
public final class JdbcRecordStore implements RecordStore {

    private static final String INSERT_SIGNAL = """
            INSERT INTO signals (seq, event_id, business_ts, strategy_id, symbol, direction,
                                 strength, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_SIGNALS = """
            SELECT event_id, business_ts, strategy_id, symbol, direction, strength, reason
            FROM signals
            WHERE business_ts >= ? AND business_ts <= ?
            ORDER BY seq ASC""";

    private static final String INSERT_INTERCEPTION = """
            INSERT INTO risk_interceptions (seq, business_ts, rule_id, rule_level, severity, detail,
                                            event_id, signal_ts, strategy_id, symbol, direction,
                                            strength, reason, equity, cash, total_notional,
                                            symbol_notional, signed_qty, price)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INTERCEPTION_COLUMNS = """
            business_ts, rule_id, rule_level, severity, detail, event_id, signal_ts, strategy_id, \
            symbol, direction, strength, reason, equity, cash, total_notional, symbol_notional, \
            signed_qty, price""";

    private static final String SELECT_INTERCEPTIONS_BY_RULE =
            "SELECT " + INTERCEPTION_COLUMNS + " FROM risk_interceptions WHERE rule_id = ? "
                    + "ORDER BY seq ASC";

    private static final String SELECT_INTERCEPTIONS_BY_RANGE =
            "SELECT " + INTERCEPTION_COLUMNS + " FROM risk_interceptions "
                    + "WHERE business_ts >= ? AND business_ts <= ? ORDER BY seq ASC";

    private static final String INSERT_EQUITY = """
            INSERT INTO equity_snapshot (seq, business_ts, equity)
            VALUES (?, ?, ?)""";

    private static final String SELECT_EQUITY = """
            SELECT business_ts, equity
            FROM equity_snapshot
            WHERE business_ts >= ? AND business_ts <= ?
            ORDER BY seq ASC""";

    private static final String INSERT_POSITION = """
            INSERT INTO positions (seq, business_ts, symbol, direction, qty, entry_price, mark_price)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_POSITIONS = """
            SELECT business_ts, symbol, direction, qty, entry_price, mark_price
            FROM positions
            WHERE business_ts >= ? AND business_ts <= ?
            ORDER BY seq ASC""";

    private final DataSource dataSource;

    public JdbcRecordStore(DataSource dataSource) {
        this.dataSource = dataSource;
        BusinessSchema.createOn(dataSource);
    }

    @Override
    public void saveSignal(SignalEvent signal) {
        String what = "save signal " + signal.strategyId() + "/" + signal.symbol().unified();
        append(what, "signals", INSERT_SIGNAL, insert -> {
            insert.setLong(2, signal.eventId());
            insert.setLong(3, signal.timestamp());
            insert.setString(4, signal.strategyId());
            insert.setString(5, signal.symbol().unified());
            insert.setString(6, signal.direction().name());
            // Text, not setDouble: a numeric binding delivers NaN to the driver as NULL, and the
            // column is NOT NULL. See BusinessSchema for the half this does not cover.
            insert.setString(7, Double.toString(signal.strength()));
            insert.setString(8, signal.reason());
        });
    }

    @Override
    public void saveInterception(InterceptionRecord interception) {
        SignalFacts facts = interception.facts();
        RiskRejection rejection = interception.rejection();
        SignalEvent signal = facts.signal();
        append("save interception " + rejection.ruleId(), "risk_interceptions", INSERT_INTERCEPTION,
                insert -> {
                    insert.setLong(2, facts.nowMillis());
                    insert.setString(3, rejection.ruleId());
                    insert.setString(4, rejection.level().name());
                    insert.setString(5, rejection.severity().name());
                    insert.setString(6, rejection.detail());
                    insert.setLong(7, signal.eventId());
                    insert.setLong(8, signal.timestamp());
                    insert.setString(9, signal.strategyId());
                    insert.setString(10, signal.symbol().unified());
                    insert.setString(11, signal.direction().name());
                    insert.setString(12, Double.toString(signal.strength()));
                    insert.setString(13, signal.reason());
                    setDecimal(insert, 14, facts.equity());
                    setDecimal(insert, 15, facts.cash());
                    setDecimal(insert, 16, facts.totalNotional());
                    setDecimal(insert, 17, facts.symbolNotional());
                    setDecimal(insert, 18, facts.signedQty());
                    setDecimal(insert, 19, facts.price());
                });
    }

    @Override
    public void saveEquitySnapshot(EquitySnapshot snapshot) {
        append("save equity snapshot", "equity_snapshot", INSERT_EQUITY, insert -> {
            insert.setLong(2, snapshot.businessTs());
            setDecimal(insert, 3, snapshot.equity());
        });
    }

    @Override
    public void savePosition(PositionSnapshot snapshot) {
        Position position = snapshot.position();
        append("save position " + position.symbol().unified(), "positions", INSERT_POSITION,
                insert -> {
                    insert.setLong(2, snapshot.businessTs());
                    insert.setString(3, position.symbol().unified());
                    insert.setString(4, position.direction().name());
                    setDecimal(insert, 5, position.qty());
                    setDecimal(insert, 6, position.entryPrice());
                    setDecimal(insert, 7, snapshot.markPrice());
                });
    }

    @Override
    public List<SignalEvent> signals(long from, long to) {
        return range("list signals", SELECT_SIGNALS, from, to, JdbcRecordStore::signal);
    }

    @Override
    public List<InterceptionRecord> interceptions(String ruleId) {
        return read("list interceptions of " + ruleId, connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_INTERCEPTIONS_BY_RULE)) {
                select.setString(1, ruleId);
                try (ResultSet rows = select.executeQuery()) {
                    return collect(rows, JdbcRecordStore::interception);
                }
            }
        });
    }

    @Override
    public List<InterceptionRecord> interceptions(long from, long to) {
        return range("list interceptions", SELECT_INTERCEPTIONS_BY_RANGE, from, to,
                JdbcRecordStore::interception);
    }

    @Override
    public List<EquitySnapshot> equitySnapshots(long from, long to) {
        return range("list equity snapshots", SELECT_EQUITY, from, to, JdbcRecordStore::equity);
    }

    @Override
    public List<PositionSnapshot> positions(long from, long to) {
        return range("list positions", SELECT_POSITIONS, from, to, JdbcRecordStore::position);
    }

    /**
     * The whole write path, and the only reason it is one method: every save is the same two steps -
     * allocate {@code seq}, then let the caller bind columns 2..n - and column 1 is bound here so no
     * save can forget it or bind it twice. Indexes in the callers start at 2 as a result. {@code table}
     * and {@code insertSql} are always the matching pair from this file; they are two arguments rather
     * than one because {@code seq} is per table and a statement cannot be asked which table it names.
     */
    private void append(String what, String table, String insertSql, ColumnBinding binding) {
        write(what, connection -> {
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setLong(1, nextSeq(connection, table));
                binding.bind(insert);
                insert.executeUpdate();
            }
            return null;
        });
    }

    private <T> List<T> range(String what, String select, long from, long to, RowMapper<T> mapper) {
        return read(what, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setLong(1, from);
                statement.setLong(2, to);
                try (ResultSet rows = statement.executeQuery()) {
                    return collect(rows, mapper);
                }
            }
        });
    }

    private static SignalEvent signal(ResultSet rows) throws SQLException {
        return new SignalEvent(rows.getLong("event_id"), rows.getLong("business_ts"),
                rows.getString("strategy_id"), Symbol.parse(rows.getString("symbol")),
                Direction.valueOf(rows.getString("direction")), strength(rows),
                rows.getString("reason"));
    }

    private static InterceptionRecord interception(ResultSet rows) throws SQLException {
        SignalEvent signal = new SignalEvent(rows.getLong("event_id"), rows.getLong("signal_ts"),
                rows.getString("strategy_id"), Symbol.parse(rows.getString("symbol")),
                Direction.valueOf(rows.getString("direction")), strength(rows),
                rows.getString("reason"));
        SignalFacts facts = new SignalFacts(signal, decimal(rows, "equity"), decimal(rows, "cash"),
                decimal(rows, "total_notional"), decimal(rows, "symbol_notional"),
                decimal(rows, "signed_qty"), decimal(rows, "price"), rows.getLong("business_ts"));
        RiskRejection rejection = new RiskRejection(rows.getString("rule_id"),
                RiskRule.Level.valueOf(rows.getString("rule_level")),
                RiskAlertEvent.Severity.valueOf(rows.getString("severity")),
                rows.getString("detail"));
        return new InterceptionRecord(facts, rejection);
    }

    private static EquitySnapshot equity(ResultSet rows) throws SQLException {
        return new EquitySnapshot(rows.getLong("business_ts"), decimal(rows, "equity"));
    }

    private static PositionSnapshot position(ResultSet rows) throws SQLException {
        return new PositionSnapshot(rows.getLong("business_ts"),
                new Position(Symbol.parse(rows.getString("symbol")),
                        Direction.valueOf(rows.getString("direction")),
                        decimal(rows, "qty"), decimal(rows, "entry_price")),
                decimal(rows, "mark_price"));
    }

    private static double strength(ResultSet rows) throws SQLException {
        String text = rows.getString("strength");
        if (text == null) {
            throw new SQLException("Column strength is NULL but declared NOT NULL");
        }
        return Double.parseDouble(text);
    }

    private static <T> List<T> collect(ResultSet rows, RowMapper<T> mapper) throws SQLException {
        List<T> collected = new ArrayList<>();
        while (rows.next()) {
            collected.add(mapper.map(rows));
        }
        return List.copyOf(collected);
    }

    /**
     * {@code table} is one of four literals passed from the four {@code save} methods above. It is
     * concatenated because a table name cannot be a bind parameter, not because anything outside this
     * file reaches it.
     */
    private static long nextSeq(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(seq), 0) + 1 FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private <T> T read(String what, ConnectionWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw store(what, e);
        }
    }

    private <T> T write(String what, ConnectionWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw store(what, e);
        }
    }

    /**
     * {@code toPlainString}, for the reason {@code JdbcOrderStore} gives: {@code toString} writes
     * {@code 0E-8} for a flat book's zero, which reads back identical but is unreadable in a client.
     */
    private static void setDecimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        statement.setString(index, value.toPlainString());
    }

    private static BigDecimal decimal(ResultSet rows, String column) throws SQLException {
        String text = rows.getString(column);
        if (text == null) {
            throw new SQLException("Column " + column + " is NULL but declared NOT NULL");
        }
        return new BigDecimal(text);
    }

    private static IllegalStateException store(String what, SQLException cause) {
        return new IllegalStateException("Record store failure while trying to " + what
                + " (sqlState " + cause.getSQLState() + ")", cause);
    }

    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private interface ColumnBinding {
        void bind(PreparedStatement insert) throws SQLException;
    }
}
