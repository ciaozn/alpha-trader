package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The {@code signals} table is the only place "what did the strategies emit" is written down, so these
 * tests hold it to two things: that a signal on the bus becomes a row whatever else happens to it, and
 * that the row is the same signal the gate's interception row names - the join FR-RK-08's record is
 * useless without.
 *
 * <p>The engine cases run a real {@link EventEngine} rather than calling {@code onEvent} twice in the
 * order the wiring happens to use, because both claims are about the engine's behaviour and not the
 * recorder's: dispatch order is what decides which row lands first, and dispatch swallowing a handler's
 * exception is what makes a throwing gate leave no trace of its own.
 *
 * <p>Reads after {@code awaitQuiescence} need no synchronized decorator. The engine writes
 * {@code completedThrough} under the quiescence lock after the round's handlers have run and the waiting
 * thread reads it under the same lock, so the barrier is also the happens-before edge - unlike
 * {@code OrderSenderTest}, which reads a store its worker thread may still be writing.
 */
class SignalRecorderTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final TradingRules BTC_RULES =
            new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("20"));
    private static final long T0 = 1_700_000_000_000L;
    private static final String STRATEGY = "ma-cross-btc";

    private final InMemoryRecordStore records = new InMemoryRecordStore();
    private final SignalRecorder recorder = new SignalRecorder(records);

    private EventEngine engine;

    @AfterEach
    void stopTheEngine() {
        if (engine != null) {
            // The loop thread is not a daemon, so a test that leaves one running holds the fork open.
            engine.stop();
        }
    }

    private static SignalEvent signal(Direction direction, long at) {
        return SignalEvent.of(STRATEGY, BTC, direction, 1.0, "test", at);
    }

    private List<SignalEvent> everySignal() {
        return records.signals(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    // ------------------------------------------------------------------ what it writes

    @Test
    void aSignalBecomesARowAndNothingElseDoes() {
        SignalEvent recorded = signal(Direction.LONG, T0);

        recorder.onEvent(recorded, SignalRecorderTest::refuseToPublish);
        recorder.onEvent(FillEvent.of("f1", BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("1"),
                BigDecimal.ZERO, T0), SignalRecorderTest::refuseToPublish);
        recorder.onEvent(OrderRequestEvent.of("c1", BTC, Side.BUY, OrderType.MARKET, BigDecimal.ONE, null,
                T0), SignalRecorderTest::refuseToPublish);
        recorder.onEvent(RiskAlertEvent.of("RK-01", RiskAlertEvent.Severity.INFO, "x", T0),
                SignalRecorderTest::refuseToPublish);

        assertThat(everySignal()).containsExactly(recorded);
    }

    /**
     * The recorder is handed a publisher that fails the test. It is a sink: a recorder that emitted
     * something would be a second source of signals on the bus, and the cascade it started would be
     * recorded by the next round as though a strategy had produced it.
     */
    private static void refuseToPublish(Event event) {
        fail("a recorder publishes nothing, but it published " + event);
    }

    @Test
    void theRowIsTheEventItselfSoTheJoinKeyIsTheOneTheBusMinted() {
        SignalEvent recorded = signal(Direction.SHORT, T0);

        recorder.onEvent(recorded, SignalRecorderTest::refuseToPublish);

        SignalEvent row = everySignal().getFirst();
        // Same object, not an equal one: event ids come from a process-wide counter, so a recorder that
        // rebuilt the event would join to nothing.
        assertThat(row).isSameAs(recorded);
        assertThat(row.eventId()).isEqualTo(recorded.eventId());
        assertThat(row.strategyId()).isEqualTo(STRATEGY);
        assertThat(row.direction()).isEqualTo(Direction.SHORT);
    }

    @Test
    void signalsComeBackInTheOrderTheyArrivedAndOnlyInsideTheirOwnWindow() {
        SignalEvent first = signal(Direction.LONG, T0);
        SignalEvent second = signal(Direction.FLAT, T0 + 1_000);
        SignalEvent third = signal(Direction.SHORT, T0 + 2_000);
        for (SignalEvent signal : List.of(first, second, third)) {
            recorder.onEvent(signal, SignalRecorderTest::refuseToPublish);
        }

        // Insertion order, never an ordering that depends on a hash or on the symbol (NFR-04).
        assertThat(everySignal()).containsExactly(first, second, third);
        // Both ends inclusive, so a window that starts and ends on the middle signal finds exactly it.
        assertThat(records.signals(T0 + 1_000, T0 + 1_000)).containsExactly(second);
        assertThat(records.signals(T0, T0 + 2_000)).containsExactly(first, second, third);
        // And "none" is an empty list, never null and never every row.
        assertThat(records.signals(T0 + 2_001, T0 + 3_000)).isEmpty();
    }

    // ------------------------------------------------------------------ the join (FR-RK-08)

    /**
     * Builds the pair the way the wiring does - recorder ahead of gate - and returns the started engine.
     * Cash 50 at a 30% target cannot reach the 20 minimum notional, so every signal is refused by
     * FR-RK-07 without needing a stub rule.
     */
    private EventEngine engineWithAGate(RiskPipeline pipeline) {
        Portfolio portfolio = new Portfolio(new BigDecimal("50"));
        portfolio.mark(BTC, new BigDecimal("100"));
        VirtualClock clock = new VirtualClock(T0);
        RiskGate gate = new RiskGate(portfolio,
                new PositionSizer(new PositionSizer.Policy(new BigDecimal("0.30"))),
                FixedTradingRulesProvider.of(BTC_RULES), pipeline, clock, records);

        engine = new EventEngine(EventJournal.noop(), clock);
        engine.registerHandler(recorder);
        engine.registerHandler(gate);
        engine.start();
        return engine;
    }

    private void replay(SignalEvent signal) {
        try {
            engine.publish(signal);
            // A false here means the round never closed, and every assertion below would then be reading
            // a store the engine thread is still writing - so it aborts the test rather than being ignored.
            assertThat(engine.awaitQuiescence(signal.eventId())).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for the round to close");
        }
    }

    @Test
    void anInterceptionJoinsBackToTheSignalItRefused() {
        engineWithAGate(RiskPipeline.empty());
        SignalEvent refused = signal(Direction.LONG, T0);

        replay(refused);

        List<InterceptionRecord> interceptions = records.interceptions(PositionSizer.RULE_MIN_NOTIONAL);
        assertThat(interceptions).hasSize(1);
        assertThat(everySignal()).containsExactly(refused);
        // The acceptance criterion: the refusal names a signal that is actually in the signals table, so
        // "which signal did this rule refuse, and what did the strategy say when it emitted it" is one
        // join rather than a guess from matching timestamps.
        assertThat(interceptions.getFirst().facts().signal().eventId()).isEqualTo(refused.eventId());
        assertThat(records.signals(refused.timestamp(), refused.timestamp())).containsExactly(refused);
    }

    @Test
    void theSignalRowIsWrittenBeforeTheInterceptionRowThatNamesIt() {
        WriteOrder writes = new WriteOrder(records);
        Portfolio portfolio = new Portfolio(new BigDecimal("50"));
        portfolio.mark(BTC, new BigDecimal("100"));
        VirtualClock clock = new VirtualClock(T0);
        engine = new EventEngine(EventJournal.noop(), clock);
        // Same order the wiring uses, but against a store that notes the sequence: registration order is
        // otherwise invisible, because the two rows land in two different tables and each reads back fine
        // on its own.
        engine.registerHandler(new SignalRecorder(writes));
        engine.registerHandler(new RiskGate(portfolio,
                new PositionSizer(new PositionSizer.Policy(new BigDecimal("0.30"))),
                FixedTradingRulesProvider.of(BTC_RULES), RiskPipeline.empty(), clock, writes));
        engine.start();

        SignalEvent refused = signal(Direction.LONG, T0);
        try {
            engine.publish(refused);
            assertThat(engine.awaitQuiescence(refused.eventId())).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for the round to close");
        }

        // Signal first. The other order leaves an interception whose signal_event_id names a row that does
        // not exist if the process dies between the two writes - a broken join in exactly the query the
        // pair of tables exists to answer, where this order leaves only a signal that was not refused.
        assertThat(writes.writes).containsExactly("signal:" + refused.eventId(),
                "interception:" + refused.eventId());
    }

    /** Notes the order writes arrived in. Delegating keeps the rows queryable by the assertions above. */
    private static final class WriteOrder implements RecordStore {

        private final RecordStore delegate;
        private final List<String> writes = new CopyOnWriteArrayList<>();

        WriteOrder(RecordStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveSignal(SignalEvent signal) {
            writes.add("signal:" + signal.eventId());
            delegate.saveSignal(signal);
        }

        @Override
        public void saveInterception(InterceptionRecord interception) {
            writes.add("interception:" + interception.facts().signal().eventId());
            delegate.saveInterception(interception);
        }

        @Override
        public void saveEquitySnapshot(EquitySnapshot snapshot) {
            writes.add("equity");
            delegate.saveEquitySnapshot(snapshot);
        }

        @Override
        public void savePosition(PositionSnapshot position) {
            writes.add("position");
            delegate.savePosition(position);
        }

        @Override
        public List<SignalEvent> signals(long from, long to) {
            return delegate.signals(from, to);
        }

        @Override
        public List<InterceptionRecord> interceptions(String ruleId) {
            return delegate.interceptions(ruleId);
        }

        @Override
        public List<InterceptionRecord> interceptions(long from, long to) {
            return delegate.interceptions(from, to);
        }

        @Override
        public List<EquitySnapshot> equitySnapshots(long from, long to) {
            return delegate.equitySnapshots(from, to);
        }

        @Override
        public List<PositionSnapshot> positions(long from, long to) {
            return delegate.positions(from, to);
        }
    }

    // ------------------------------------------------------------------ why it is not inside the gate

    @Test
    void aGateThatThrowsMidDecisionStillLeavesTheSignalRecorded() {
        ThrowingRule buggy = new ThrowingRule();
        engineWithAGate(new RiskPipeline(List.of(buggy), List.of()));

        replay(signal(Direction.LONG, T0));

        // The rule was reached, so the gate ran and the exception escaped it: without this the case would
        // also pass if the gate were never called at all, which is the opposite of the point.
        assertThat(buggy.consulted).isTrue();
        // dispatch logs a handler's exception and carries on, so a rule with a bug in it leaves the gate
        // with neither an alert nor an interception row: nothing at all. The signal row is the only
        // evidence of what was in flight, and it exists because the recorder is not the gate.
        assertThat(everySignal()).hasSize(1);
        assertThat(everySignal().getFirst().strategyId()).isEqualTo(STRATEGY);
        assertThat(records.interceptions(Long.MIN_VALUE, Long.MAX_VALUE)).isEmpty();
        assertThat(engine.isRunning()).as("the engine survives a handler that throws").isTrue();
    }

    /** Stands for a bug in a rule, which is the ordinary way a gate stops answering for a signal. */
    private static final class ThrowingRule implements SignalRule {

        private volatile boolean consulted;

        @Override
        public String ruleId() {
            return "RK-02-leverage";
        }

        @Override
        public RiskRule.Level level() {
            return RiskRule.Level.ACCOUNT;
        }

        @Override
        public Optional<RiskRejection> check(SignalFacts facts) {
            consulted = true;
            throw new IllegalStateException("a rule with a bug in it");
        }
    }

    @Test
    void aSignalNoGateEverSawIsStillASignalThatWasEmitted() {
        engine = new EventEngine(EventJournal.noop(), new VirtualClock(T0));
        engine.registerHandler(recorder);
        engine.start();

        SignalEvent emitted = signal(Direction.LONG, T0);
        replay(emitted);

        // No gate in this wiring at all, and the row is still there: "what the strategies emitted" is not
        // a question whose answer depends on risk being installed. Recording inside the gate would make
        // it one.
        assertThat(everySignal()).containsExactly(emitted);
    }

    @Test
    void everySignalIsRecordedOncePerArrival() {
        engineWithAGate(RiskPipeline.empty());
        List<SignalEvent> sent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            SignalEvent signal = signal(Direction.LONG, T0 + i);
            sent.add(signal);
            replay(signal);
        }

        assertThat(everySignal()).containsExactlyElementsOf(sent);
        assertThat(records.interceptions(PositionSizer.RULE_MIN_NOTIONAL)).hasSize(3);
    }
}
