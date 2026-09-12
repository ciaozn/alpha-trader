package com.ciaozn.alphatrader.app.data;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.List;

/**
 * The one DDL for the business database: DESIGN §11's four tables plus the two 取舍 15 adds
 * ({@code positions}, {@code risk_interceptions}), because FR-RK-08's "拦截记录可查询，包含命中的
 * 规则与当时账户状态" and EX-04/EX-06's position corrections have nowhere to live in four.
 *
 * <p><b>All six statements live in one class, and whichever store is constructed first creates all
 * of them.</b> The file is one database with one schema, so the answer to "what does this system
 * record?" is one list; splitting it would put four tables in {@code JdbcRecordStore} and two here,
 * and the day a column is added to one of them the two files would disagree about what the database
 * is. {@code IF NOT EXISTS} is what makes the construction order between the two stores irrelevant,
 * and what makes a restart over an existing file a no-op rather than a failure.
 *
 * <p><b>Dialect-neutral for the tables, branched for the indexes</b> - SQLite for local development, MySQL 8 on the server,
 * no migration tool, tested only against SQLite until P4-8 (取舍 15). That rules out: reserved words
 * as column names ({@code order_status} not {@code status}, {@code rule_level} not {@code level},
 * {@code interval_code} in the klines table for the same reason); dialect-specific upsert; and
 * auto-increment, whose spelling is {@code AUTOINCREMENT} on one and {@code AUTO_INCREMENT} on the
 * other. Ordering columns are therefore allocated by the stores, not by the database.
 *
 * <p><b>Prices, quantities, money and signal strength are {@code VARCHAR}, never {@code DECIMAL},
 * {@code REAL} or {@code DOUBLE}</b> (取舍 5, restated by 取舍 15 for these tables). SQLite's NUMERIC
 * affinity turns {@code '68000.10'} into a double and MySQL's {@code DECIMAL(24,8)} rescales it to
 * {@code 68000.10000000}, so either choice makes the number a backtest replays differ from the
 * number the exchange sent - silently, and in exactly the values SC-02 compares bit for bit. No
 * statement here does arithmetic on those columns, so text costs nothing.
 *
 * <p>{@code strength} shipped first as {@code DOUBLE}, on the argument that a double is not money and
 * an 8-byte IEEE value is the same on both dialects. Reverted, that column loses {@code -0.0}, which
 * SQLite's REAL affinity hands back as {@code 0.0} - the normalization 取舍 5 exists to avoid, in the
 * one column nobody would think to check. As text it round-trips NaN, both infinities, {@code -0.0}
 * and subnormals bit for bit, and stays readable in a SQL client.
 *
 * <p><b>The column type is only half of it, and the two halves fail separately</b> - which is worth
 * stating because each one looks sufficient on its own. {@code strength} must also be <em>bound</em>
 * as text, as both stores do: bound with {@code setDouble}, a NaN reaches the driver as NULL and any
 * {@code NOT NULL} column rejects it with {@code SQLITE_CONSTRAINT_NOTNULL}, text or double. A NaN
 * strength is precisely what {@code PositionSizer.RULE_STRENGTH} refuses, so that would make the
 * interception record most worth keeping the one that cannot be written, and would throw out of the
 * gate onto the engine thread. The other direction is quieter and is why the column type still has to
 * be right: a text binding into a {@code DOUBLE} column lets NaN through by accident, because SQLite
 * cannot convert {@code "NaN"} to a number and stores the text instead.
 * {@code JdbcRecordStoreTest} pins each half by reverting the other.
 *
 * <p><b>{@code VARCHAR} widths are documentation on SQLite and a constraint on MySQL</b>, which
 * ignores and enforces them respectively. Everything written into the wide text columns
 * ({@code detail}, {@code reason}, {@code status_message}) is authored by this codebase rather than
 * by an exchange or a user, so the asymmetry fails loudly on the stricter dialect instead of
 * truncating quietly on either.
 */
final class BusinessSchema {

    /**
     * One row per order, rewritten on every lifecycle migration rather than appended to: the history
     * of an order is already in the event journal as one {@code OrderUpdateEvent} per transition, and
     * a second copy here would be a second home for the same fact that could disagree with the first.
     * {@code seq} is the position the row was first saved at, which is what makes {@code findOpen()}
     * a deterministic listing (NFR-04) instead of one ordered by whichever order traded last. It is
     * UNIQUE because a duplicated position is invisible to every read - the listing simply becomes
     * ambiguous - so the constraint is the only thing that turns an allocation bug into a failure.
     */
    private static final String ORDERS = """
            CREATE TABLE IF NOT EXISTS orders (
              client_order_id   VARCHAR(64)  NOT NULL,
              seq               BIGINT       NOT NULL UNIQUE,
              exchange_order_id VARCHAR(64),
              symbol            VARCHAR(32)  NOT NULL,
              side              VARCHAR(8)   NOT NULL,
              order_type        VARCHAR(16)  NOT NULL,
              qty               VARCHAR(64)  NOT NULL,
              limit_price       VARCHAR(64),
              filled_qty        VARCHAR(64)  NOT NULL,
              avg_fill_price    VARCHAR(64),
              order_status      VARCHAR(20)  NOT NULL,
              status_message    VARCHAR(512),
              created_at        BIGINT       NOT NULL,
              updated_at        BIGINT       NOT NULL,
              PRIMARY KEY (client_order_id)
            )""";

    /**
     * One row per execution, append-only. The four nullable {@code orders} columns are all NOT NULL
     * here: a fill is a fact that already happened, so it has a price, a quantity and a fee, and a
     * NULL in one of them would mean the store had lost part of it.
     */
    private static final String FILLS = """
            CREATE TABLE IF NOT EXISTS fills (
              seq             BIGINT      NOT NULL,
              event_id        BIGINT      NOT NULL,
              client_order_id VARCHAR(64) NOT NULL,
              symbol          VARCHAR(32) NOT NULL,
              side            VARCHAR(8)  NOT NULL,
              price           VARCHAR(64) NOT NULL,
              qty             VARCHAR(64) NOT NULL,
              fee             VARCHAR(64) NOT NULL,
              business_ts     BIGINT      NOT NULL,
              PRIMARY KEY (seq)
            )""";

    /** The strategy's output, stored whether or not anything acted on it (FR-OP-04). */
    private static final String SIGNALS = """
            CREATE TABLE IF NOT EXISTS signals (
              seq         BIGINT       NOT NULL,
              event_id    BIGINT       NOT NULL,
              business_ts BIGINT       NOT NULL,
              strategy_id VARCHAR(64)  NOT NULL,
              symbol      VARCHAR(32)  NOT NULL,
              direction   VARCHAR(8)   NOT NULL,
              strength    VARCHAR(32)  NOT NULL,
              reason      VARCHAR(512) NOT NULL,
              PRIMARY KEY (seq)
            )""";

    /**
     * Deliberately one number. The breakdown at a decision instant belongs to
     * {@code risk_interceptions}; a snapshot table that also carried cash, notional and P&L would be
     * a second book written on a timer that agrees with the first most of the time and gives no way
     * to tell which one moved when it stops agreeing.
     */
    private static final String EQUITY_SNAPSHOT = """
            CREATE TABLE IF NOT EXISTS equity_snapshot (
              seq         BIGINT      NOT NULL,
              business_ts BIGINT      NOT NULL,
              equity      VARCHAR(64) NOT NULL,
              PRIMARY KEY (seq)
            )""";

    /**
     * Position history, so that a reconciliation correcting the local book (FR-EX-04/06) leaves a
     * trace - a correction with no trace is indistinguishable from a bug that moved the book.
     * {@code mark_price} is stored because it is the one input that cannot be reconstructed later:
     * re-valuing a past position at a newer mark is how a position history quietly starts lying,
     * since the notional that breached a cap on the day no longer breaches it afterwards.
     */
    private static final String POSITIONS = """
            CREATE TABLE IF NOT EXISTS positions (
              seq         BIGINT      NOT NULL,
              business_ts BIGINT      NOT NULL,
              symbol      VARCHAR(32) NOT NULL,
              direction   VARCHAR(8)  NOT NULL,
              qty         VARCHAR(64) NOT NULL,
              entry_price VARCHAR(64) NOT NULL,
              mark_price  VARCHAR(64) NOT NULL,
              PRIMARY KEY (seq)
            )""";

    /**
     * FR-RK-08 in one row: which rule refused, and the account it refused against. The account half
     * is {@code SignalFacts} flattened out, all of it, because that is what makes the row answer
     * "was this refusal right?" instead of only "which rule said no". Storing the
     * {@code RiskAlertEvent} instead would leave a table that is queryable, names the rule, and
     * still cannot be checked - while looking complete.
     *
     * <p>{@code business_ts} is the gate's instant ({@code SignalFacts.nowMillis}); {@code signal_ts}
     * and {@code event_id} belong to the signal that was refused, which the gate saw a moment
     * earlier. Keeping both is what makes the row readable as "the strategy decided, then the gate
     * looked".
     */
    private static final String RISK_INTERCEPTIONS = """
            CREATE TABLE IF NOT EXISTS risk_interceptions (
              seq             BIGINT       NOT NULL,
              business_ts     BIGINT       NOT NULL,
              rule_id         VARCHAR(32)  NOT NULL,
              rule_level      VARCHAR(16)  NOT NULL,
              severity        VARCHAR(16)  NOT NULL,
              detail          VARCHAR(512) NOT NULL,
              event_id        BIGINT       NOT NULL,
              signal_ts       BIGINT       NOT NULL,
              strategy_id     VARCHAR(64)  NOT NULL,
              symbol          VARCHAR(32)  NOT NULL,
              direction       VARCHAR(8)   NOT NULL,
              strength        VARCHAR(32)  NOT NULL,
              reason          VARCHAR(512) NOT NULL,
              equity          VARCHAR(64)  NOT NULL,
              cash            VARCHAR(64)  NOT NULL,
              total_notional  VARCHAR(64)  NOT NULL,
              symbol_notional VARCHAR(64)  NOT NULL,
              signed_qty      VARCHAR(64)  NOT NULL,
              price           VARCHAR(64)  NOT NULL,
              PRIMARY KEY (seq)
            )""";

    /**
     * One index per column a read filters or ranges on. All five history tables are append-only and
     * grow for the whole life of a run, which {@code InMemoryRecordStore} names as the reason it is a
     * test double rather than the implementation; {@code orders} and {@code fills} are indexed too,
     * because {@code findOpen()} is what the FR-EX-04 reconciliation timer calls every 60 seconds and
     * {@code fills(client_order_id)} is called on every execution.
     *
     * <p>A range on {@code business_ts} cannot ride the {@code seq} primary key even though the two
     * usually agree: they diverge exactly when a fact arrives late, which is the case FR-EX-04 exists
     * to handle. None of these are UNIQUE - every signal on one bar shares a timestamp, and a unique
     * index here would turn a normal run into a constraint failure.
     */
    /**
     * An index to create if it is not there yet.
     *
     * <p>Structured rather than a SQL string because the statement is not portable: SQLite accepts
     * {@code CREATE INDEX IF NOT EXISTS}, MySQL rejects it outright (syntax error). The first version
     * of this class used the SQLite form and passed every test it had - because every one of them ran
     * on SQLite. Running the schema against a real MySQL server (T408) failed on the very first
     * statement, which is the argument for integration tests that a "dialect-neutral" claim needs.
     */
    private record Index(String name, String table, String columns) {

        String ddl() {
            return "CREATE INDEX " + name + " ON " + table + " (" + columns + ")";
        }
    }

    /**
     * The two products this schema is written for. Anything else fails loudly: an unknown engine may
     * accept the table DDL and then behave differently in ways no test here would catch.
     */
    private enum Dialect {
        SQLITE,
        MYSQL
    }

    private static final List<Index> INDEXES = List.of(
            new Index("idx_orders_status", "orders", "order_status"),
            new Index("idx_fills_client_order_id", "fills", "client_order_id"),
            new Index("idx_signals_business_ts", "signals", "business_ts"),
            new Index("idx_interceptions_rule_id", "risk_interceptions", "rule_id"),
            new Index("idx_interceptions_business_ts", "risk_interceptions", "business_ts"),
            new Index("idx_equity_business_ts", "equity_snapshot", "business_ts"),
            new Index("idx_positions_business_ts", "positions", "business_ts"));

    private static final List<String> STATEMENTS =
            List.of(ORDERS, FILLS, SIGNALS, EQUITY_SNAPSHOT, POSITIONS, RISK_INTERCEPTIONS);

    private BusinessSchema() {
    }

    static void createOn(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String create : STATEMENTS) {
                statement.execute(create);
            }
            Dialect dialect = dialectOf(connection);
            for (Index index : INDEXES) {
                // Checked rather than "IF NOT EXISTS": that clause exists in SQLite and not in MySQL,
                // and asking the catalog is the only form both engines agree on.
                if (!indexExists(connection, dialect, index.name())) {
                    statement.execute(index.ddl());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Business schema failure while creating the six tables of "
                    + "DESIGN §11 and their indexes (sqlState " + e.getSQLState() + ")", e);
        }
    }

    private static Dialect dialectOf(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("mysql")) {
            return Dialect.MYSQL;
        }
        if (product.contains("sqlite")) {
            return Dialect.SQLITE;
        }
        throw new IllegalStateException("Unsupported database '" + product
                + "': this schema is written for SQLite and MySQL (T408)");
    }

    private static boolean indexExists(Connection connection, Dialect dialect, String indexName)
            throws SQLException {
        String sql = dialect == Dialect.MYSQL
                ? "SELECT 1 FROM information_schema.statistics WHERE table_schema = database()"
                        + " AND index_name = ?"
                : "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, indexName);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
