package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.execution.OrderRecord;
import com.ciaozn.alphatrader.execution.OrderStore;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * {@link OrderStore} in the business database (FR-OP-04, FR-EX-01/02/04). Same shape as
 * {@link JdbcKlineRepository}: one DDL created on first use, one set of statements that run unchanged
 * on SQLite and MySQL 8, prices and quantities as text (取舍 5, 取舍 15). Lives in alpha-app because
 * that is the only module allowed to carry drivers and Spring; the contract it implements lives in
 * alpha-execution with no JDBC in sight, which is what let the order lifecycle be written and tested
 * before this class existed.
 *
 * <p><b>The listing order the contract promises is stored, not derived.</b> {@code findOpen()} must
 * return orders in first-saved order and {@code fills(id)} in the order they were stored, and SQL
 * has no insertion order to offer: {@code created_at} ties whenever two orders are opened on the same
 * bar, {@code business_ts} ties for every partial fill of one order, and {@code event_id} is
 * allocated when an event is <em>constructed</em>, so a fill saved out of construction order comes
 * back reordered. Each table therefore carries a {@code seq} the store allocates - the only ordering
 * that means "the order this store was told about them".
 *
 * <p>{@code seq} is read as {@code MAX(seq) + 1} inside the transaction that inserts, rather than
 * counted in a field seeded at construction. A field would make two live instances over one file hand
 * out the same number twice, and the second insert would fail on the uniqueness constraint - a
 * failure whose cause is invisible from the message. Reading it costs one indexed lookup per row and
 * makes the store correct no matter how many instances exist or how often the process restarts; the
 * transaction is what keeps the read and the insert from being separated by another writer, which the
 * contract already excludes by confining writes to the engine thread.
 *
 * <p><b>An update deliberately omits {@code seq} and {@code created_at}.</b> That is the SQL form of
 * "replaces the row and leaves it where it was": migrating an order from SUBMITTED to FILLED changes
 * what it is, not when it arrived, so a listing does not reshuffle every time the exchange says
 * something.
 *
 * <p>Threading: as documented on {@link OrderStore}, written on the engine thread only. Reads from
 * another thread (a monitoring endpoint, the reconciliation timer) get whatever the {@link DataSource}
 * provides; nothing here holds state between calls, so there is nothing to synchronize.
 */
public final class JdbcOrderStore implements OrderStore {

    /**
     * Derived from the enum rather than written out. A hardcoded {@code IN ('NEW','SUBMITTED',
     * 'PARTIALLY_FILLED')} keeps compiling when a seventh status is added, and reports it as closed -
     * so the new status would be invisible to reconciliation, which is exactly the order it exists to
     * notice.
     */
    private static final List<String> OPEN_STATUSES = Arrays.stream(OrderStatus.values())
            .filter(status -> !status.isTerminal())
            .map(Enum::name)
            .toList();

    private static final String ORDER_COLUMNS = """
            client_order_id, exchange_order_id, symbol, side, order_type, qty, limit_price, \
            filled_qty, avg_fill_price, order_status, status_message, created_at, updated_at""";

    private static final String SELECT_ORDER =
            "SELECT " + ORDER_COLUMNS + " FROM orders WHERE client_order_id = ?";

    private static final String SELECT_OPEN =
            "SELECT " + ORDER_COLUMNS + " FROM orders WHERE order_status IN ("
                    + String.join(", ", Collections.nCopies(OPEN_STATUSES.size(), "?"))
                    + ") ORDER BY seq ASC";

    private static final String SELECT_SEQ = "SELECT seq FROM orders WHERE client_order_id = ?";

    private static final String INSERT_ORDER = """
            INSERT INTO orders (seq, client_order_id, exchange_order_id, symbol, side, order_type,
                                qty, limit_price, filled_qty, avg_fill_price, order_status,
                                status_message, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPDATE_ORDER = """
            UPDATE orders
            SET exchange_order_id = ?, symbol = ?, side = ?, order_type = ?, qty = ?, limit_price = ?,
                filled_qty = ?, avg_fill_price = ?, order_status = ?, status_message = ?, updated_at = ?
            WHERE client_order_id = ?""";

    private static final String INSERT_FILL = """
            INSERT INTO fills (seq, event_id, client_order_id, symbol, side, price, qty, fee,
                               business_ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_FILLS = """
            SELECT event_id, client_order_id, symbol, side, price, qty, fee, business_ts
            FROM fills
            WHERE client_order_id = ?
            ORDER BY seq ASC""";

    private final DataSource dataSource;

    public JdbcOrderStore(DataSource dataSource) {
        this.dataSource = dataSource;
        BusinessSchema.createOn(dataSource);
    }

    @Override
    public void save(OrderRecord order) {
        write("save order " + order.clientOrderId(), connection -> {
            boolean known = isKnown(connection, order.clientOrderId());
            if (known) {
                try (PreparedStatement update = connection.prepareStatement(UPDATE_ORDER)) {
                    setNullableText(update, 1, order.exchangeOrderId());
                    update.setString(2, order.symbol().unified());
                    update.setString(3, order.side().name());
                    update.setString(4, order.orderType().name());
                    setDecimal(update, 5, order.qty());
                    setNullableDecimal(update, 6, order.limitPrice());
                    setDecimal(update, 7, order.filledQty());
                    setNullableDecimal(update, 8, order.avgFillPrice());
                    update.setString(9, order.status().name());
                    setNullableText(update, 10, order.statusMessage());
                    update.setLong(11, order.updatedAt());
                    update.setString(12, order.clientOrderId());
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement insert = connection.prepareStatement(INSERT_ORDER)) {
                    insert.setLong(1, nextSeq(connection, "orders"));
                    insert.setString(2, order.clientOrderId());
                    setNullableText(insert, 3, order.exchangeOrderId());
                    insert.setString(4, order.symbol().unified());
                    insert.setString(5, order.side().name());
                    insert.setString(6, order.orderType().name());
                    setDecimal(insert, 7, order.qty());
                    setNullableDecimal(insert, 8, order.limitPrice());
                    setDecimal(insert, 9, order.filledQty());
                    setNullableDecimal(insert, 10, order.avgFillPrice());
                    insert.setString(11, order.status().name());
                    setNullableText(insert, 12, order.statusMessage());
                    insert.setLong(13, order.createdAt());
                    insert.setLong(14, order.updatedAt());
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override
    public void saveFill(FillEvent fill) {
        write("save fill " + fill.clientOrderId(), connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_FILL)) {
                insert.setLong(1, nextSeq(connection, "fills"));
                insert.setLong(2, fill.eventId());
                insert.setString(3, fill.clientOrderId());
                insert.setString(4, fill.symbol().unified());
                insert.setString(5, fill.side().name());
                setDecimal(insert, 6, fill.price());
                setDecimal(insert, 7, fill.qty());
                setDecimal(insert, 8, fill.fee());
                insert.setLong(9, fill.timestamp());
                insert.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<OrderRecord> find(String clientOrderId) {
        return read("find order " + clientOrderId, connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_ORDER)) {
                select.setString(1, clientOrderId);
                try (ResultSet rows = select.executeQuery()) {
                    return rows.next() ? Optional.of(order(rows)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<OrderRecord> findOpen() {
        return read("list open orders", connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_OPEN)) {
                for (int index = 0; index < OPEN_STATUSES.size(); index++) {
                    select.setString(index + 1, OPEN_STATUSES.get(index));
                }
                try (ResultSet rows = select.executeQuery()) {
                    return collect(rows, JdbcOrderStore::order);
                }
            }
        });
    }

    @Override
    public List<FillEvent> fills(String clientOrderId) {
        return read("list fills of " + clientOrderId, connection -> {
            try (PreparedStatement select = connection.prepareStatement(SELECT_FILLS)) {
                select.setString(1, clientOrderId);
                try (ResultSet rows = select.executeQuery()) {
                    return collect(rows, JdbcOrderStore::fill);
                }
            }
        });
    }

    private static OrderRecord order(ResultSet rows) throws SQLException {
        return new OrderRecord(rows.getString("client_order_id"),
                rows.getString("exchange_order_id"),
                Symbol.parse(rows.getString("symbol")),
                Side.valueOf(rows.getString("side")),
                OrderType.valueOf(rows.getString("order_type")),
                decimal(rows, "qty"),
                nullableDecimal(rows, "limit_price"),
                decimal(rows, "filled_qty"),
                nullableDecimal(rows, "avg_fill_price"),
                OrderStatus.valueOf(rows.getString("order_status")),
                rows.getString("status_message"),
                rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    private static FillEvent fill(ResultSet rows) throws SQLException {
        return new FillEvent(rows.getLong("event_id"), rows.getLong("business_ts"),
                rows.getString("client_order_id"), Symbol.parse(rows.getString("symbol")),
                Side.valueOf(rows.getString("side")), decimal(rows, "price"),
                decimal(rows, "qty"), decimal(rows, "fee"));
    }

    private static <T> List<T> collect(ResultSet rows, RowMapper<T> mapper) throws SQLException {
        List<T> collected = new ArrayList<>();
        while (rows.next()) {
            collected.add(mapper.map(rows));
        }
        return List.copyOf(collected);
    }

    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private boolean isKnown(Connection connection, String clientOrderId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_SEQ)) {
            select.setString(1, clientOrderId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * {@code table} is one of two literals in this class. It is concatenated because a table name
     * cannot be a bind parameter, not because anything outside this file reaches it.
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
     * {@code toPlainString}, not {@code toString}: the second writes {@code 0E-8} for the zero a new
     * order is created with, which reads back to the same value but leaves the column unreadable to
     * anyone opening the file with a SQL client. For every scale these columns actually hold they are
     * the same string, and this is the one {@link JdbcKlineRepository} already chose.
     */
    private static void setDecimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        statement.setString(index, value.toPlainString());
    }

    /**
     * The nullable columns are written as SQL NULL and read back as Java null, because that is what
     * they mean: {@code avg_fill_price} is NULL until something fills, and a zero there would say the
     * order filled at price 0. A {@code DEFAULT 0} on the column or a {@code COALESCE} in the read
     * would both turn "the exchange said nothing" into "the exchange said zero".
     */
    private static void setNullableDecimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toPlainString());
        }
    }

    private static void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static BigDecimal decimal(ResultSet rows, String column) throws SQLException {
        String text = rows.getString(column);
        if (text == null) {
            throw new SQLException("Column " + column + " is NULL but declared NOT NULL");
        }
        return new BigDecimal(text);
    }

    private static BigDecimal nullableDecimal(ResultSet rows, String column) throws SQLException {
        String text = rows.getString(column);
        return text == null ? null : new BigDecimal(text);
    }

    private static IllegalStateException store(String what, SQLException cause) {
        return new IllegalStateException("Order store failure while trying to " + what
                + " (sqlState " + cause.getSQLState() + ")", cause);
    }
}
