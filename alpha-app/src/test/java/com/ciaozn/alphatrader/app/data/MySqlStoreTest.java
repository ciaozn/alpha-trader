package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.app.config.StoreProperties;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.execution.OrderRecord;
import com.ciaozn.alphatrader.risk.EquitySnapshot;
import com.ciaozn.alphatrader.risk.InterceptionRecord;
import com.ciaozn.alphatrader.risk.PositionSnapshot;
import com.ciaozn.alphatrader.risk.RiskRejection;
import com.ciaozn.alphatrader.risk.RiskRule;
import com.ciaozn.alphatrader.risk.SignalFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T408's other half: the same stores, against a real MySQL server.
 *
 * <p><b>Why this is gated on an environment variable.</b> CI has no MySQL, and a test that skips
 * silently is worse than one that never runs - so it is off unless {@code ALPHA_TEST_MYSQL_URL} says
 * where a server is, exactly like the testnet-gated integration test. Locally:
 *
 * <pre>
 *   ALPHA_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3307/alpha_trader \
 *   ALPHA_TEST_MYSQL_USER=alpha ALPHA_TEST_MYSQL_PASSWORD=... mvn -pl alpha-app test -Dtest=MySqlStoreTest
 * </pre>
 *
 * <p><b>What it proves that the SQLite tests cannot.</b> The dialect-neutral schema was written to run
 * on both, and "runs on SQLite" is not evidence for MySQL: reserved words, column types, index syntax
 * and the {@code SELECT-then-INSERT/UPDATE} sequence all behave differently there. Every row this test
 * writes is read back through the same interface the production code uses, so a column that silently
 * truncates or a text field that comes back padded shows up here.
 */
@EnabledIfEnvironmentVariable(named = "ALPHA_TEST_MYSQL_URL", matches = ".+")
class MySqlStoreTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");

    private static DataSource dataSource() {
        return StoreDataSource.create(new StoreProperties(
                System.getenv("ALPHA_TEST_MYSQL_URL"),
                System.getenv("ALPHA_TEST_MYSQL_USER"),
                System.getenv("ALPHA_TEST_MYSQL_PASSWORD")));
    }

    @Test
    void theSixTablesAreCreatedInMySQL() throws Exception {
        DataSource dataSource = dataSource();
        new JdbcOrderStore(dataSource);
        new JdbcRecordStore(dataSource);

        assertThat(tables(dataSource)).contains("orders", "fills", "signals", "equity_snapshot",
                "positions", "risk_interceptions");
    }

    @Test
    void ordersAndFillsRoundTripThroughMySQL() throws Exception {
        DataSource dataSource = dataSource();
        JdbcOrderStore orders = new JdbcOrderStore(dataSource);
        String id = "mysql-" + UUID.randomUUID();
        long now = 1_700_000_000_000L;

        orders.save(OrderRecord.ofNew(OrderRequestEvent.of(id, BTC, Side.BUY, OrderType.MARKET,
                new BigDecimal("0.250"), null, now), now));
        orders.saveFill(FillEvent.of(id, BTC, Side.BUY, new BigDecimal("51000.25"),
                new BigDecimal("0.250"), new BigDecimal("0.13"), now + 1));

        OrderRecord read = orders.find(id).orElseThrow();
        assertThat(read.symbol().unified()).isEqualTo("BTCUSDT.PERP");
        // Price and quantity are stored as text precisely so they survive this trip unchanged: a
        // NUMERIC column would be entitled to return 0.25 or 0.2500000, and the book compares exactly.
        assertThat(read.qty()).isEqualByComparingTo(new BigDecimal("0.250"));
        assertThat(orders.fills(id)).hasSize(1);
        assertThat(orders.fills(id).get(0).price()).isEqualByComparingTo(new BigDecimal("51000.25"));
        assertThat(orders.findOpen()).extracting(OrderRecord::clientOrderId).contains(id);
    }

    @Test
    void recordedHistoryRoundTripsThroughMySQL() throws Exception {
        DataSource dataSource = dataSource();
        JdbcRecordStore records = new JdbcRecordStore(dataSource);
        String strategyId = "mysql-" + UUID.randomUUID();
        long now = 1_700_000_000_000L;

        SignalEvent signal = SignalEvent.of(strategyId, BTC, Direction.LONG, 1.0, "cross", now);
        records.saveSignal(signal);
        SignalFacts facts = new SignalFacts(signal, new BigDecimal("1000"), new BigDecimal("1000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50000"), now);
        records.saveInterception(new InterceptionRecord(facts,
                new RiskRejection("RK-03-order", RiskRule.Level.ORDER,
                        RiskAlertEvent.Severity.CRITICAL, "too big")));
        records.saveEquitySnapshot(new EquitySnapshot(now, new BigDecimal("1000.12345678")));

        Portfolio portfolio = new Portfolio(new BigDecimal("1000"));
        portfolio.mark(BTC, new BigDecimal("50000"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.01"), BigDecimal.ZERO);
        records.savePosition(new PositionSnapshot(now, portfolio.position(BTC), new BigDecimal("50000")));

        assertThat(records.signals(now, now)).extracting(SignalEvent::strategyId).contains(strategyId);
        List<InterceptionRecord> interceptions = new ArrayList<>(records.interceptions("RK-03-order"));
        assertThat(interceptions).anySatisfy(record -> assertThat(record.facts().signal().strategyId())
                .isEqualTo(strategyId));
        assertThat(records.equitySnapshots(now, now)).anySatisfy(snapshot ->
                assertThat(snapshot.equity()).isEqualByComparingTo(new BigDecimal("1000.12345678")));
        assertThat(records.positions(now, now)).anySatisfy(snapshot ->
                assertThat(snapshot.markPrice()).isEqualByComparingTo(new BigDecimal("50000")));
    }

    private static List<String> tables(DataSource dataSource) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = database()")) {
            while (rows.next()) {
                names.add(rows.getString(1).toLowerCase());
            }
        }
        return names;
    }
}
