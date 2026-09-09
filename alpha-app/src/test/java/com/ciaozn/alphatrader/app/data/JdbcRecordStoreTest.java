package com.ciaozn.alphatrader.app.data;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;
import com.ciaozn.alphatrader.execution.OrderRecord;
import com.ciaozn.alphatrader.risk.EquitySnapshot;
import com.ciaozn.alphatrader.risk.InterceptionRecord;
import com.ciaozn.alphatrader.risk.PositionSnapshot;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RecordStore;
import com.ciaozn.alphatrader.risk.RiskRejection;
import com.ciaozn.alphatrader.risk.RiskRule;
import com.ciaozn.alphatrader.risk.SignalFacts;
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
 * The SQL half of the contract {@code InMemoryRecordStoreTest} pins in memory - that file says so, and
 * the seven tests it holds are repeated here against a real SQLite file because repeating them is the
 * only way to know the two implementations answer the same question the same way.
 *
 * <p>The assertions worth reading are the ones a {@code List} cannot fail and a database can:
 * <ul>
 *   <li><b>insertion order surviving the absence of any insertion order in SQL.</b> All four reads
 *       promise "the order they arrived", and {@code business_ts} cannot express it - every signal on
 *       one bar shares a timestamp, and a fact can arrive late. That is what {@code seq} is for;</li>
 *   <li><b>scale surviving a column type.</b> The in-memory test asserts the account numbers with
 *       {@code isEqualByComparingTo}, which is scale-insensitive and therefore cannot notice a
 *       {@code DECIMAL(24,8)} column rescaling {@code 940.1} to {@code 940.10000000}. Here they are
 *       {@code isEqualTo}, which can (取舍 15);</li>
 *   <li><b>{@code strength} surviving as text, in the column <em>and</em> in the binding.</b> It
 *       shipped as {@code DOUBLE} and was measured wrong. The two halves fail separately - the column
 *       loses {@code -0.0} to REAL affinity, a numeric binding loses NaN to a NOT NULL violation - so
 *       each of the two tests below was verified by reverting only the other half. See
 *       {@link BusinessSchema};</li>
 *   <li><b>a rule id matching exactly.</b> {@code interceptions("RK-02")} must not return the rows
 *       belonging to {@code RK-02-account}; a {@code LIKE} would, and a {@code List} filter by
 *       {@code equals} could not.</li>
 * </ul>
 */
class JdbcRecordStoreTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final String STRATEGY = "ma-cross-btc";
    private static final String OTHER_STRATEGY = "rsi-reversal-eth";

    @TempDir
    Path directory;

    private SQLiteDataSource dataSource() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("business.db"));
        return dataSource;
    }

    private JdbcRecordStore store() {
        return new JdbcRecordStore(dataSource());
    }

    private static SignalEvent signal(String strategyId, long businessTs) {
        return signal(strategyId, businessTs, 1.0);
    }

    private static SignalEvent signal(String strategyId, long businessTs, double strength) {
        return SignalEvent.of(strategyId, BTC, Direction.LONG, strength, "golden cross", businessTs);
    }

    /** A book that has traded and been marked down, so the snapshot has numbers worth losing. */
    private static Portfolio underwaterBook() {
        Portfolio book = new Portfolio(new BigDecimal("10000"));
        book.applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO);
        book.mark(BTC, new BigDecimal("94"));
        return book;
    }

    private static InterceptionRecord interception(String ruleId, RiskRule.Level level, long businessTs) {
        return interception(ruleId, level, businessTs, 1.0);
    }

    /**
     * The signal is a millisecond older than the snapshot: the strategy decided, then the gate looked.
     * {@code InterceptionRecord.timestamp()} is the gate's instant, and this is what says so.
     */
    private static InterceptionRecord interception(String ruleId, RiskRule.Level level, long businessTs,
                                                   double strength) {
        return new InterceptionRecord(
                SignalFacts.of(signal(STRATEGY, businessTs - 1, strength), underwaterBook(), businessTs),
                new RiskRejection(ruleId, level, RiskAlertEvent.Severity.CRITICAL, "refused by " + ruleId));
    }

    /**
     * Hand-built rather than derived from a {@code Portfolio}, so the six money columns go in with
     * scales no book would produce and the read-back assertion is about the column, not about
     * {@code Portfolio}'s arithmetic.
     */
    private static SignalFacts scaledFacts() {
        return new SignalFacts(signal(STRATEGY, T0 - 1),
                new BigDecimal("9940.10"),
                new BigDecimal("10000.00"),
                new BigDecimal("940.1000"),
                new BigDecimal("0.15000000"),
                new BigDecimal("-0.1500"),
                new BigDecimal("68000.10"),
                T0);
    }

    // ------------------------------------------------------------------ the schema

    @Test
    void whicheverStoreArrivesFirstLeavesTheOtherACompleteDatabase() throws SQLException {
        SQLiteDataSource dataSource = dataSource();
        new JdbcRecordStore(dataSource);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            List<String> tables = names(statement, "SELECT name FROM sqlite_master WHERE type = 'table'");
            assertThat(tables).as("all six, not the four this store writes")
                    .containsExactlyInAnyOrder("orders", "fills", "signals", "equity_snapshot",
                            "positions", "risk_interceptions");

            List<String> indexes = names(statement,
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%'");
            assertThat(indexes).containsExactlyInAnyOrder("idx_orders_status", "idx_fills_client_order_id",
                    "idx_signals_business_ts", "idx_interceptions_rule_id", "idx_interceptions_business_ts",
                    "idx_equity_business_ts", "idx_positions_business_ts");
        }

        // The other store over the same file must find its tables already there and be able to write
        // them: IF NOT EXISTS is what makes the construction order between the two irrelevant.
        JdbcOrderStore orders = new JdbcOrderStore(dataSource);
        orders.save(OrderRecord.ofNew(OrderRequestEvent.of("ma-cross-btc-1", BTC, Side.BUY,
                OrderType.MARKET, new BigDecimal("0.1500"), null, T0), T0));
        assertThat(orders.find("ma-cross-btc-1")).isPresent();
        assertThat(orders.findOpen()).extracting(OrderRecord::clientOrderId)
                .containsExactly("ma-cross-btc-1");
    }

    private static List<String> names(Statement statement, String query) throws SQLException {
        List<String> found = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                found.add(rows.getString("name"));
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ round trips

    @Test
    void anInterceptionCarriesTheRuleThatRefusedAndTheAccountItRefusedAgainst() {
        InterceptionRecord written = interception("RK-02-account", RiskRule.Level.ACCOUNT, T0);
        store().saveInterception(written);

        // A fresh instance over the same file, and equals across all nineteen columns at once:
        // InterceptionRecord -> SignalFacts -> SignalEvent, with BigDecimal.equals scale-sensitive on
        // the six money columns. One assertion, the whole row.
        InterceptionRecord read = store().interceptions(T0, T0).getFirst();
        assertThat(read).isEqualTo(written);

        // Spelled out as well, because a single equals that fails says nothing about which column did.
        assertThat(read.ruleId()).isEqualTo("RK-02-account");
        assertThat(read.rejection().level()).isEqualTo(RiskRule.Level.ACCOUNT);
        assertThat(read.rejection().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(read.rejection().detail()).isEqualTo("refused by RK-02-account");
        assertThat(read.timestamp()).as("indexed by when the gate looked").isEqualTo(T0);
        assertThat(read.facts().signal().timestamp()).as("the signal keeps the strategy's instant")
                .isEqualTo(T0 - 1);

        // The half a RiskAlertEvent does not carry: 10 BTC bought at 100 and marked down to 94 on a
        // 10000 account. The book is a margin book, so opening the position moved no cash - the loss
        // is unrealized, and equity is 10000 minus 60.
        SignalFacts facts = read.facts();
        assertThat(facts.signal().strategyId()).isEqualTo(STRATEGY);
        assertThat(facts.symbol()).isEqualTo(BTC);
        assertThat(facts.signal().direction()).isEqualTo(Direction.LONG);
        assertThat(facts.signal().reason()).isEqualTo("golden cross");
        assertThat(facts.signal().strength()).isEqualTo(1.0);
        assertThat(facts.cash()).isEqualByComparingTo("10000");
        assertThat(facts.equity()).isEqualByComparingTo("9940");
        assertThat(facts.totalNotional()).isEqualByComparingTo("940");
        assertThat(facts.signedQty()).isEqualByComparingTo("10");
        assertThat(facts.price()).isEqualByComparingTo("94");
    }

    @Test
    void interceptionsComeBackByRuleAndOnlyByThatRule() {
        JdbcRecordStore store = store();
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0));
        store.saveInterception(interception("RK-05-breaker", RiskRule.Level.CIRCUIT_BREAKER, T0 + 1));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0 + 2));

        assertThat(store.interceptions("RK-02-account"))
                .as("insertion order, not the order the rule ids happen to sort into")
                .extracting(InterceptionRecord::timestamp)
                .containsExactly(T0, T0 + 2);
        assertThat(store.interceptions("RK-05-breaker")).hasSize(1);
        assertThat(store.interceptions("RK-07-no-trading-rules")).isEmpty();
        assertThat(store.interceptions("RK-02"))
                .as("equality, not LIKE: a rule id is not a prefix search")
                .isEmpty();
    }

    @Test
    void snapshotsComeBackWithTheScaleTheyWentInWith() {
        Position position = new Position(BTC, Direction.SHORT, new BigDecimal("0.1500"),
                new BigDecimal("68000.10"));
        JdbcRecordStore store = store();
        store.saveEquitySnapshot(new EquitySnapshot(T0, new BigDecimal("8940.10")));
        store.savePosition(new PositionSnapshot(T0, position, new BigDecimal("67999.90000000")));

        // isEqualTo on a BigDecimal is scale-sensitive, so these are the assertions that fail the day a
        // NUMERIC or DECIMAL column normalizes 68000.10 to 68000.1 (取舍 15). Across a reopened store,
        // so they are about the column and not about an object that never left memory.
        EquitySnapshot equity = store().equitySnapshots(T0, T0).getFirst();
        assertThat(equity.equity()).isEqualTo(new BigDecimal("8940.10"));
        PositionSnapshot stored = store().positions(T0, T0).getFirst();
        assertThat(stored.position()).isEqualTo(position);
        assertThat(stored.position().qty()).isEqualTo(new BigDecimal("0.1500"));
        assertThat(stored.position().entryPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(stored.markPrice()).isEqualTo(new BigDecimal("67999.90000000"));
    }

    @Test
    void anInterceptionKeepsTheScaleOfEveryNumberItCarries() {
        store().saveInterception(new InterceptionRecord(scaledFacts(),
                new RiskRejection("RK-03-order", RiskRule.Level.PORTFOLIO,
                        RiskAlertEvent.Severity.WARNING, "single-symbol cap")));

        SignalFacts read = store().interceptions("RK-03-order").getFirst().facts();
        assertThat(read.equity()).isEqualTo(new BigDecimal("9940.10"));
        assertThat(read.cash()).isEqualTo(new BigDecimal("10000.00"));
        assertThat(read.totalNotional()).isEqualTo(new BigDecimal("940.1000"));
        assertThat(read.symbolNotional()).isEqualTo(new BigDecimal("0.15000000"));
        assertThat(read.signedQty()).isEqualTo(new BigDecimal("-0.1500"));
        assertThat(read.price()).isEqualTo(new BigDecimal("68000.10"));
    }

    // ------------------------------------------------------------------ strength as text

    @Test
    void strengthSurvivesEveryDoubleThatMadeTheColumnText() {
        // Reverting either strength column to DOUBLE fails this on -0.0 alone: NaN gets through a text
        // binding into a REAL-affinity column by accident, since SQLite cannot convert "NaN" and stores
        // the text. The array is not padding - -0.0 is the value that catches the column type and NaN is
        // the one the companion test catches on the binding side.
        //
        // Both tables are probed with the same values because they hold the same column and a DDL edit
        // reaches only one of them: probing signals alone left risk_interceptions.strength free to go
        // back to DOUBLE without a single test noticing.
        double[] values = {1.0, 0.75, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -0.0, Double.MIN_VALUE};
        JdbcRecordStore store = store();
        for (double value : values) {
            store.saveSignal(signal(STRATEGY, T0, value));
            store.saveInterception(interception(PositionSizer.RULE_STRENGTH, RiskRule.Level.SIZING,
                    T0, value));
        }

        // All fourteen rows share one timestamp, so these two reads also prove the ordering comes from
        // seq and that neither business_ts index is unique.
        List<SignalEvent> signals = store.signals(T0, T0);
        List<InterceptionRecord> refusals = store.interceptions(PositionSizer.RULE_STRENGTH);
        assertThat(signals).hasSize(values.length);
        assertThat(refusals).hasSize(values.length);
        for (int index = 0; index < values.length; index++) {
            long expected = Double.doubleToLongBits(values[index]);
            assertThat(Double.doubleToLongBits(signals.get(index).strength()))
                    .as("signals.strength[%d] written as %s", index, values[index])
                    .isEqualTo(expected);
            assertThat(Double.doubleToLongBits(refusals.get(index).facts().signal().strength()))
                    .as("risk_interceptions.strength[%d] written as %s", index, values[index])
                    .isEqualTo(expected);
        }
    }

    @Test
    void anInterceptionWhoseSignalStrengthIsNotANumberIsStillRecorded() {
        // Pins the BINDING rather than the column: strength goes in as Double.toString and comes out
        // as parseDouble, and swapping in setDouble makes a NaN reach the driver as NULL, which any
        // NOT NULL column rejects with SQLITE_CONSTRAINT_NOTNULL - checked by reverting just that, on
        // both tables. PositionSizer refuses a strength outside [0, 1] with a check written as
        // !(strength >= 0 && strength <= 1), which is how NaN is caught, so this is the refusal most
        // worth auditing and the one a numeric binding would throw out of the gate onto the engine
        // thread instead of recording.
        InterceptionRecord written = interception(PositionSizer.RULE_STRENGTH, RiskRule.Level.SIZING,
                T0, Double.NaN);
        store().saveInterception(written);

        InterceptionRecord read = store().interceptions(PositionSizer.RULE_STRENGTH).getFirst();
        assertThat(read).isEqualTo(written);
        assertThat(read.facts().signal().strength()).isNaN();
        assertThat(read.rejection().level()).isEqualTo(RiskRule.Level.SIZING);
    }

    // ------------------------------------------------------------------ ordering and ranges

    @Test
    void everyHistoryReadIncludesBothEndsOfItsRangeAndExcludesWhatIsOutside() {
        long from = T0 + 10;
        long to = T0 + 20;
        JdbcRecordStore store = store();
        for (long at : new long[] {from - 1, from, to, to + 1}) {
            store.saveSignal(signal(STRATEGY, at));
            store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, at));
            store.saveEquitySnapshot(new EquitySnapshot(at, BigDecimal.ONE));
            store.savePosition(new PositionSnapshot(at, Position.flat(BTC), BigDecimal.ONE));
        }

        // Four separate SQL predicates, so four separate chances for an off-by-one: "between" is where
        // one goes to be invisible.
        assertThat(store.signals(from, to)).extracting(SignalEvent::timestamp).containsExactly(from, to);
        assertThat(store.interceptions(from, to)).extracting(InterceptionRecord::timestamp)
                .containsExactly(from, to);
        assertThat(store.equitySnapshots(from, to)).extracting(EquitySnapshot::businessTs)
                .containsExactly(from, to);
        assertThat(store.positions(from, to)).extracting(PositionSnapshot::businessTs)
                .containsExactly(from, to);
    }

    @Test
    void theFourReadsReportWhatWasSavedNotWhatTheTimestampsSay() {
        // Saved newest first, as happens when a REST catch-up delivers what a WebSocket push missed.
        // Ordering by business_ts would return the opposite of the contract's "the order they arrived"
        // and would look plausible doing it, because most of the time the two agree.
        JdbcRecordStore store = store();
        store.saveSignal(signal(STRATEGY, T0 + 5));
        store.saveSignal(signal(OTHER_STRATEGY, T0 + 1));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0 + 5));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0 + 1));
        store.saveEquitySnapshot(new EquitySnapshot(T0 + 5, new BigDecimal("9940")));
        store.saveEquitySnapshot(new EquitySnapshot(T0 + 1, new BigDecimal("9950")));
        store.savePosition(new PositionSnapshot(T0 + 5, Position.flat(BTC), new BigDecimal("94")));
        store.savePosition(new PositionSnapshot(T0 + 1, Position.flat(BTC), new BigDecimal("95")));

        assertThat(store.signals(T0, T0 + 9)).extracting(SignalEvent::strategyId)
                .containsExactly(STRATEGY, OTHER_STRATEGY);
        assertThat(store.interceptions("RK-02-account")).extracting(InterceptionRecord::timestamp)
                .containsExactly(T0 + 5, T0 + 1);
        assertThat(store.interceptions(T0, T0 + 9)).extracting(InterceptionRecord::timestamp)
                .containsExactly(T0 + 5, T0 + 1);
        assertThat(store.equitySnapshots(T0, T0 + 9)).extracting(EquitySnapshot::equity)
                .containsExactly(new BigDecimal("9940"), new BigDecimal("9950"));
        assertThat(store.positions(T0, T0 + 9)).extracting(PositionSnapshot::markPrice)
                .containsExactly(new BigDecimal("94"), new BigDecimal("95"));
    }

    @Test
    void twoLiveStoresOverOneFileDoNotClaimTheSamePositionTwice() {
        // Both constructed before either writes. A store that seeded a counter field at construction
        // would hand out seq 1 twice and the second insert would fail on the primary key - a message
        // naming the constraint and not its cause. Reading MAX(seq) + 1 inside the inserting
        // transaction is what makes two instances over one file correct.
        JdbcRecordStore first = store();
        JdbcRecordStore second = store();

        first.saveSignal(signal(STRATEGY, T0));
        second.saveSignal(signal(OTHER_STRATEGY, T0 + 1));
        first.saveEquitySnapshot(new EquitySnapshot(T0 + 2, BigDecimal.ONE));
        second.saveEquitySnapshot(new EquitySnapshot(T0 + 3, BigDecimal.ONE));

        assertThat(first.signals(T0, T0 + 9)).extracting(SignalEvent::strategyId)
                .containsExactly(STRATEGY, OTHER_STRATEGY);
        assertThat(second.signals(T0, T0 + 9)).extracting(SignalEvent::strategyId)
                .containsExactly(STRATEGY, OTHER_STRATEGY);
        assertThat(first.equitySnapshots(T0, T0 + 9)).hasSize(2);
    }

    // ------------------------------------------------------------------ emptiness and immutability

    @Test
    void anEmptyStoreAnswersEveryQueryWithNothing() {
        RecordStore store = store();
        assertThat(store.signals(0, Long.MAX_VALUE)).isEmpty();
        assertThat(store.interceptions("RK-02-account")).isEmpty();
        assertThat(store.interceptions(0, Long.MAX_VALUE)).isEmpty();
        assertThat(store.equitySnapshots(0, Long.MAX_VALUE)).isEmpty();
        assertThat(store.positions(0, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void aReaderCannotWriteThroughTheListItWasHanded() {
        JdbcRecordStore store = store();
        store.saveSignal(signal(STRATEGY, T0));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0));
        store.saveEquitySnapshot(new EquitySnapshot(T0, BigDecimal.ONE));
        store.savePosition(new PositionSnapshot(T0, Position.flat(BTC), BigDecimal.ONE));

        assertThatThrownBy(() -> store.signals(T0, T0).add(signal("injected", T0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.interceptions("RK-02-account").clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.interceptions(T0, T0).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.equitySnapshots(T0, T0).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> store.positions(T0, T0).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSignalIsStoredWhetherOrNotAnythingActedOnIt() {
        // FR-OP-04's signals table is the strategy's output, not the gate's: a signal that sized to
        // no trade and one that was refused are both facts about what the strategy saw.
        SignalEvent first = signal(STRATEGY, T0);
        SignalEvent second = signal(OTHER_STRATEGY, T0 + 1);
        JdbcRecordStore store = store();
        store.saveSignal(first);
        store.saveSignal(second);

        // Across a reopened store, and equals over all seven columns at once - event_id included,
        // which is the one nothing else in this file reads back.
        List<SignalEvent> read = store().signals(T0, T0 + 1);
        assertThat(read).containsExactly(first, second);
        assertThat(read.getFirst().strength()).isEqualTo(1.0);
        assertThat(read.getFirst().reason()).isEqualTo("golden cross");
    }
}
