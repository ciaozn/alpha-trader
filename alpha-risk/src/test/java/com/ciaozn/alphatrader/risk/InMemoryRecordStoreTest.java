package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T309: the {@link RecordStore} contract, exercised against the implementation that has nothing to
 * hide behind.
 *
 * <p>These are the assertions T311's JDBC store must also pass, which is why they are written against
 * the interface and why two of them are stricter than a round-trip usually is.
 *
 * <p>The interception test asserts the <b>account state</b> came back, not just the rule id. FR-RK-08
 * asks for both halves and only one of them is in the {@code RiskAlertEvent}, so a store that persists
 * the alert rather than the record would pass every assertion about rule ids while losing the numbers
 * that make a refusal arguable - and would look complete doing it.
 *
 * <p>The range test asserts inclusivity at <b>both</b> ends for all four history reads. One shared
 * helper makes that redundant here, and that is precisely why it is spelled out: T311 writes four
 * separate SQL predicates, and "between" is where an off-by-one goes to be invisible.
 */
class InMemoryRecordStoreTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final String STRATEGY = "ma-cross-btc";

    private final InMemoryRecordStore store = new InMemoryRecordStore();

    private static SignalEvent signal(String strategyId, long businessTs) {
        return SignalEvent.of(strategyId, BTC, Direction.LONG, 1.0, "golden cross", businessTs);
    }

    /** A book that has traded and been marked down, so the snapshot has numbers worth losing. */
    private static Portfolio underwaterBook() {
        Portfolio book = new Portfolio(new BigDecimal("10000"));
        book.applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO);
        book.mark(BTC, new BigDecimal("94"));
        return book;
    }

    private static InterceptionRecord interception(String ruleId, RiskRule.Level level, long businessTs) {
        // The signal is a millisecond older than the snapshot: the strategy decided, then the gate
        // looked. InterceptionRecord.timestamp() is the gate's instant, and this is what says so.
        return new InterceptionRecord(
                SignalFacts.of(signal(STRATEGY, businessTs - 1), underwaterBook(), businessTs),
                new RiskRejection(ruleId, level, RiskAlertEvent.Severity.CRITICAL, "refused by " + ruleId));
    }

    // ------------------------------------------------------------------ round trips

    @Test
    void anInterceptionCarriesTheRuleThatRefusedAndTheAccountItRefusedAgainst() {
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0));

        InterceptionRecord read = store.interceptions(T0, T0).getFirst();
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
        assertThat(facts.cash()).isEqualByComparingTo("10000");
        assertThat(facts.equity()).isEqualByComparingTo("9940");
        assertThat(facts.totalNotional()).isEqualByComparingTo("940");
        assertThat(facts.signedQty()).isEqualByComparingTo("10");
        assertThat(facts.price()).isEqualByComparingTo("94");
    }

    @Test
    void interceptionsComeBackByRuleAndOnlyByThatRule() {
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0));
        store.saveInterception(interception("RK-05-breaker", RiskRule.Level.CIRCUIT_BREAKER, T0 + 1));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, T0 + 2));

        assertThat(store.interceptions("RK-02-account"))
                .as("insertion order, not the order the rule ids happen to sort into")
                .extracting(InterceptionRecord::timestamp)
                .containsExactly(T0, T0 + 2);
        assertThat(store.interceptions("RK-05-breaker")).hasSize(1);
        assertThat(store.interceptions("RK-07-no-trading-rules")).isEmpty();
    }

    @Test
    void everyHistoryReadIncludesBothEndsOfItsRangeAndExcludesWhatIsOutside() {
        long from = T0 + 10;
        long to = T0 + 20;
        store.saveSignal(signal(STRATEGY, from - 1));
        store.saveSignal(signal(STRATEGY, from));
        store.saveSignal(signal(STRATEGY, to));
        store.saveSignal(signal(STRATEGY, to + 1));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, from - 1));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, from));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, to));
        store.saveInterception(interception("RK-02-account", RiskRule.Level.ACCOUNT, to + 1));
        store.saveEquitySnapshot(new EquitySnapshot(from - 1, BigDecimal.ONE));
        store.saveEquitySnapshot(new EquitySnapshot(from, BigDecimal.ONE));
        store.saveEquitySnapshot(new EquitySnapshot(to, BigDecimal.ONE));
        store.saveEquitySnapshot(new EquitySnapshot(to + 1, BigDecimal.ONE));
        store.savePosition(new PositionSnapshot(from - 1, Position.flat(BTC), BigDecimal.ONE));
        store.savePosition(new PositionSnapshot(from, Position.flat(BTC), BigDecimal.ONE));
        store.savePosition(new PositionSnapshot(to, Position.flat(BTC), BigDecimal.ONE));
        store.savePosition(new PositionSnapshot(to + 1, Position.flat(BTC), BigDecimal.ONE));

        assertThat(store.signals(from, to)).extracting(SignalEvent::timestamp).containsExactly(from, to);
        assertThat(store.interceptions(from, to)).extracting(InterceptionRecord::timestamp)
                .containsExactly(from, to);
        assertThat(store.equitySnapshots(from, to)).extracting(EquitySnapshot::businessTs)
                .containsExactly(from, to);
        assertThat(store.positions(from, to)).extracting(PositionSnapshot::businessTs)
                .containsExactly(from, to);
    }

    @Test
    void snapshotsComeBackWithTheScaleTheyWentInWith() {
        Position position = new Position(BTC, Direction.SHORT, new BigDecimal("0.1500"),
                new BigDecimal("68000.10"));
        store.saveEquitySnapshot(new EquitySnapshot(T0, new BigDecimal("8940.10")));
        store.savePosition(new PositionSnapshot(T0, position, new BigDecimal("67999.90000000")));

        // isEqualTo on a BigDecimal is scale-sensitive, so this is the assertion that fails the day a
        // NUMERIC or DECIMAL column normalizes 68000.10 to 68000.1 (取舍 15).
        EquitySnapshot equity = store.equitySnapshots(T0, T0).getFirst();
        assertThat(equity.equity()).isEqualTo(new BigDecimal("8940.10"));
        PositionSnapshot stored = store.positions(T0, T0).getFirst();
        assertThat(stored.position()).isEqualTo(position);
        assertThat(stored.position().qty()).isEqualTo(new BigDecimal("0.1500"));
        assertThat(stored.position().entryPrice()).isEqualTo(new BigDecimal("68000.10"));
        assertThat(stored.markPrice()).isEqualTo(new BigDecimal("67999.90000000"));
    }

    // ------------------------------------------------------------------ emptiness and immutability

    @Test
    void anEmptyStoreAnswersEveryQueryWithNothing() {
        assertThat(store.signals(0, Long.MAX_VALUE)).isEmpty();
        assertThat(store.interceptions("RK-02-account")).isEmpty();
        assertThat(store.interceptions(0, Long.MAX_VALUE)).isEmpty();
        assertThat(store.equitySnapshots(0, Long.MAX_VALUE)).isEmpty();
        assertThat(store.positions(0, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void aReaderCannotWriteThroughTheListItWasHanded() {
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
        store.saveSignal(signal("ma-cross-btc", T0));
        store.saveSignal(signal("rsi-reversal-eth", T0 + 1));

        List<SignalEvent> read = store.signals(T0, T0 + 1);
        assertThat(read).extracting(SignalEvent::strategyId)
                .containsExactly("ma-cross-btc", "rsi-reversal-eth");
        assertThat(read.getFirst().strength()).isEqualTo(1.0);
        assertThat(read.getFirst().reason()).isEqualTo("golden cross");
    }
}
