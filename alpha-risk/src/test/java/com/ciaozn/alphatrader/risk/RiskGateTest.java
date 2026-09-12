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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gate is the only thing standing between a strategy's opinion and an order (FR-RK-01), so
 * these tests assert both directions: what comes out when a signal is sound, and that nothing
 * comes out - only an alert (FR-RK-08) - when it is not.
 */
class RiskGateTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final TradingRules BTC_RULES =
            new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("20"));
    private static final long T0 = 1_700_000_000_000L;

    private final List<Event> published = new ArrayList<>();

    private record Harness(Portfolio portfolio, VirtualClock clock, RiskGate gate, InMemoryRecordStore records) {
    }

    private Harness harness(String cash, TradingRules... rules) {
        return harness(RiskPipeline.empty(), cash, rules);
    }

    private Harness harness(RiskPipeline pipeline, String cash, TradingRules... rules) {
        Portfolio portfolio = new Portfolio(new BigDecimal(cash));
        VirtualClock clock = new VirtualClock(T0);
        InMemoryRecordStore records = new InMemoryRecordStore();
        // Explicit 0.30 rather than Policy.DEFAULT: this harness tests gate ordering, not exposure
        // arithmetic, and several cases below hand-compute a target of 30 and apply fills of 30 to
        // stand for an already-held position. The default's own value is pinned in PositionSizerTest.
        RiskGate gate = new RiskGate(portfolio,
                new PositionSizer(new PositionSizer.Policy(new BigDecimal("0.30"))),
                FixedTradingRulesProvider.of(rules), pipeline, clock, records);
        return new Harness(portfolio, clock, gate, records);
    }

    private static SignalEvent signal(String strategyId, Symbol symbol, Direction direction, double strength) {
        return SignalEvent.of(strategyId, symbol, direction, strength, "test", T0);
    }

    private static List<OrderRequestEvent> orders(List<Event> events) {
        return events.stream().filter(OrderRequestEvent.class::isInstance)
                .map(OrderRequestEvent.class::cast).toList();
    }

    private static List<RiskAlertEvent> alerts(List<Event> events) {
        return events.stream().filter(RiskAlertEvent.class::isInstance)
                .map(RiskAlertEvent.class::cast).toList();
    }

    @Test
    void turnsALongSignalIntoAMarketOrder() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).hasSize(1);
        OrderRequestEvent order = orders(published).getFirst();
        assertThat(order.symbol()).isEqualTo(BTC);
        assertThat(order.side()).isEqualTo(Side.BUY);
        assertThat(order.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.qty()).isEqualTo(new BigDecimal("30.000"));
        // a market order carries no price: the matcher fills at the next open, the OMS at the touch
        assertThat(order.price()).isNull();
        assertThat(order.timestamp()).isEqualTo(T0);
        assertThat(order.clientOrderId()).startsWith("ma-cross-btc-");
        assertThat(harness.gate().ordersPassed()).isEqualTo(1);
        assertThat(harness.gate().signalsBlocked()).isZero();
        assertThat(alerts(published)).isEmpty();
    }

    @Test
    void closesAHeldPositionOnAFlatSignal() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("30"), BigDecimal.ZERO);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), published::add);

        OrderRequestEvent order = orders(published).getFirst();
        assertThat(order.side()).isEqualTo(Side.SELL);
        assertThat(order.qty()).isEqualTo(new BigDecimal("30.000"));
    }

    @Test
    void givesEveryOrderAUniqueExchangeLegalId() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);
        harness.clock().advanceTo(T0 + 3_600_000L);
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        List<String> ids = orders(published).stream().map(OrderRequestEvent::clientOrderId).toList();
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> {
            assertThat(id).hasSizeLessThanOrEqualTo(36);
            assertThat(id).matches("[.A-Za-z0-9:/_-]{1,36}");
        });
    }

    @Test
    void twoIdenticalGatesProduceIdenticalOrders() {
        List<Event> firstPublished = new ArrayList<>();
        List<Event> secondPublished = new ArrayList<>();
        replay(harness("10000", BTC_RULES), firstPublished);
        replay(harness("10000", BTC_RULES), secondPublished);

        assertThat(orders(firstPublished)).hasSize(2);
        // eventIds come from a process-wide counter, everything the exchange sees must match (NFR-04)
        assertThat(describe(firstPublished)).isEqualTo(describe(secondPublished));
    }

    /** One open-then-close round trip, so both the quantity and the id sequence are compared. */
    private static void replay(Harness harness, List<Event> out) {
        harness.portfolio().mark(BTC, new BigDecimal("100"));
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), out::add);
        harness.portfolio().applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("30"), BigDecimal.ZERO);
        harness.clock().advanceTo(T0 + 3_600_000L);
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), out::add);
    }

    private static List<String> describe(List<Event> events) {
        return orders(events).stream()
                .map(order -> order.clientOrderId() + "|" + order.symbol().unified() + "|" + order.side()
                        + "|" + order.orderType() + "|" + order.qty().toPlainString() + "|" + order.timestamp())
                .toList();
    }

    @Test
    void blocksAnOrderBelowTheExchangeMinimumAndAlertsInstead() {
        Harness harness = harness("50", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).isEmpty();
        assertThat(alerts(published)).hasSize(1);
        RiskAlertEvent alert = alerts(published).getFirst();
        assertThat(alert.ruleId()).isEqualTo(PositionSizer.RULE_MIN_NOTIONAL);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(alert.detail()).contains("ma-cross-btc").contains("BTCUSDT").contains("LONG");
        assertThat(harness.gate().signalsBlocked()).isEqualTo(1);
        assertThat(harness.gate().ordersPassed()).isZero();
    }

    @Test
    void missingTradingRulesAreAHardStop() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(ETH, new BigDecimal("3000"));

        harness.gate().onEvent(signal("ma-cross-eth", ETH, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).isEmpty();
        RiskAlertEvent alert = alerts(published).getFirst();
        assertThat(alert.ruleId()).isEqualTo(RiskGate.RULE_MISSING_TRADING_RULES);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(alert.detail()).contains("ETHUSDT");
    }

    @Test
    void anUnmarkedSymbolCannotBeSized() {
        Harness harness = harness("10000", BTC_RULES);

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).isEmpty();
        RiskAlertEvent alert = alerts(published).getFirst();
        assertThat(alert.ruleId()).isEqualTo(PositionSizer.RULE_NO_PRICE);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
    }

    @Test
    void aFlatSignalWhileFlatPublishesNothing() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), published::add);

        assertThat(published).isEmpty();
        assertThat(harness.gate().ordersPassed()).isZero();
        assertThat(harness.gate().signalsBlocked()).isZero();
    }

    @Test
    void ignoresEverythingThatIsNotASignal() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(FillEvent.of("f1", BTC, Side.BUY, new BigDecimal("100"),
                new BigDecimal("1"), BigDecimal.ZERO, T0), published::add);
        harness.gate().onEvent(OrderRequestEvent.of("x", BTC, Side.BUY, OrderType.MARKET,
                BigDecimal.ONE, null, T0), published::add);
        harness.gate().onEvent(RiskAlertEvent.of("RK-01", RiskAlertEvent.Severity.INFO, "x", T0),
                published::add);

        assertThat(published).isEmpty();
    }

    // ------------------------------------------------------------------ the pipeline (FR-RK-01)

    /** Records what the gate showed it and returns a fixed verdict; null verdict means "no objection". */
    private static final class StubSignalRule implements SignalRule {

        private final String ruleId;
        private final RiskRule.Level level;
        private final RiskRejection verdict;
        private final List<SignalFacts> seen = new ArrayList<>();

        StubSignalRule(String ruleId, RiskRule.Level level, RiskRejection verdict) {
            this.ruleId = ruleId;
            this.level = level;
            this.verdict = verdict;
        }

        @Override
        public String ruleId() {
            return ruleId;
        }

        @Override
        public RiskRule.Level level() {
            return level;
        }

        @Override
        public Optional<RiskRejection> check(SignalFacts facts) {
            seen.add(facts);
            return Optional.ofNullable(verdict);
        }
    }

    private static final class StubOrderRule implements OrderRule {

        private final String ruleId;
        private final RiskRule.Level level;
        private final RiskRejection verdict;
        private final List<OrderFacts> seen = new ArrayList<>();

        StubOrderRule(String ruleId, RiskRule.Level level, RiskRejection verdict) {
            this.ruleId = ruleId;
            this.level = level;
            this.verdict = verdict;
        }

        @Override
        public String ruleId() {
            return ruleId;
        }

        @Override
        public RiskRule.Level level() {
            return level;
        }

        @Override
        public Optional<RiskRejection> check(OrderFacts facts) {
            seen.add(facts);
            return Optional.ofNullable(verdict);
        }
    }

    private static RiskRejection rejection(String ruleId, RiskRule.Level level) {
        return new RiskRejection(ruleId, level, RiskAlertEvent.Severity.WARNING, "stub objection");
    }

    @Test
    void aSignalStageRuleRejectsBeforeTheSignalIsEverSized() {
        StubSignalRule breaker = new StubSignalRule("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER,
                rejection("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER));
        Harness harness = harness(new RiskPipeline(List.of(breaker), List.of()), "10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        // Strength 0 sizes to "no trade", which publishes nothing at all. The alert below can only
        // exist because the pipeline ran first - sizing first would have ended the attempt silently
        // and the breaker would never have learned it was asked.
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 0.0), published::add);

        assertThat(orders(published)).isEmpty();
        assertThat(alerts(published)).hasSize(1);
        assertThat(alerts(published).getFirst().ruleId()).isEqualTo("RK-05-daily-loss");
        assertThat(breaker.seen).hasSize(1);
        assertThat(harness.gate().signalsBlocked()).isEqualTo(1);
        assertThat(harness.gate().ordersPassed()).isZero();
    }

    @Test
    void anOrderStageRuleSeesTheCandidateQuantityAndCanBlockAnOtherwiseValidOrder() {
        StubOrderRule cap = new StubOrderRule("RK-03-order-notional", RiskRule.Level.ORDER,
                rejection("RK-03-order-notional", RiskRule.Level.ORDER));
        Harness harness = harness(new RiskPipeline(List.of(), List.of(cap)), "10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).isEmpty();
        assertThat(alerts(published)).hasSize(1);
        assertThat(alerts(published).getFirst().ruleId()).isEqualTo("RK-03-order-notional");
        assertThat(harness.gate().signalsBlocked()).isEqualTo(1);
        // What the rule was shown is the point of the second stage: the sized quantity, and the
        // notional at the same mark the sizer used.
        assertThat(cap.seen).hasSize(1);
        assertThat(cap.seen.getFirst().side()).isEqualTo(Side.BUY);
        assertThat(cap.seen.getFirst().qty()).isEqualByComparingTo("30.000");
        assertThat(cap.seen.getFirst().orderNotional()).isEqualByComparingTo("3000");
        assertThat(cap.seen.getFirst().projectedTotalNotional()).isEqualByComparingTo("3000");
    }

    @Test
    void anOrderStageRuleIsNotConsultedWhenSizingProducesNoOrder() {
        StubOrderRule cap = new StubOrderRule("RK-03-order-notional", RiskRule.Level.ORDER, null);
        Harness harness = harness(new RiskPipeline(List.of(), List.of(cap)), "10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), published::add);

        assertThat(cap.seen).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void bothStagesAndTheSizerSeeOneSnapshotOfTheAccount() {
        StubSignalRule early = new StubSignalRule("RK-02-leverage", RiskRule.Level.ACCOUNT, null);
        StubOrderRule late = new StubOrderRule("RK-04-symbol-exposure", RiskRule.Level.PORTFOLIO, null);
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        portfolio.mark(BTC, new BigDecimal("100"));
        InMemoryRecordStore records = new InMemoryRecordStore();
        // The mover is first because it has to be consulted between the snapshot and the sizing: the book
        // it moves is the book the sizer would read if the gate re-read it instead of reusing the snapshot.
        RiskGate gate = new RiskGate(portfolio,
                new PositionSizer(new PositionSizer.Policy(new BigDecimal("0.30"))),
                FixedTradingRulesProvider.of(BTC_RULES),
                new RiskPipeline(List.of(new BookMovingRule(portfolio, BTC, new BigDecimal("200")), early),
                        List.of(late)),
                new VirtualClock(T0), records);

        gate.onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).hasSize(1);
        assertThat(late.seen).hasSize(1);
        // Same object, not merely equal values: a mark landing mid-decision would otherwise let the
        // alert quote an account state that is not the one the decision was made against.
        assertThat(late.seen.getFirst().signal()).isSameAs(early.seen.getFirst());
        // And the sizer, which the rule above tried to move out from under it: 30% of 10000 at the
        // snapshot's 100 is 30, where a fresh read at 200 would have sized 15. The quantity is the only
        // place the sizer's input is observable, so it is what pins the sizer to the same snapshot.
        assertThat(orders(published).getFirst().qty()).isEqualByComparingTo("30.000");
    }

    /**
     * Stands for a mark arriving while the gate is mid-decision, which in live is ordinary. It objects to
     * nothing: a rule that objected would end the attempt before the sizer ran, and the sized quantity is
     * the only thing that shows what the sizer was handed.
     */
    private static final class BookMovingRule implements SignalRule {

        private final Portfolio portfolio;
        private final Symbol symbol;
        private final BigDecimal price;

        BookMovingRule(Portfolio portfolio, Symbol symbol, BigDecimal price) {
            this.portfolio = portfolio;
            this.symbol = symbol;
            this.price = price;
        }

        @Override
        public String ruleId() {
            return "RK-00-book-mover";
        }

        @Override
        public RiskRule.Level level() {
            return RiskRule.Level.ACCOUNT;
        }

        @Override
        public Optional<RiskRejection> check(SignalFacts facts) {
            portfolio.mark(symbol, price);
            return Optional.empty();
        }
    }

    @Test
    void missingTradingRulesIsCheckedBeforeThePipelineRuns() {
        StubSignalRule breaker = new StubSignalRule("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER, null);
        Harness harness = harness(new RiskPipeline(List.of(breaker), List.of()), "10000", BTC_RULES);
        harness.portfolio().mark(ETH, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-eth", ETH, Direction.LONG, 1.0), published::add);

        assertThat(breaker.seen).isEmpty();
        assertThat(alerts(published)).hasSize(1);
        assertThat(alerts(published).getFirst().ruleId()).isEqualTo(RiskGate.RULE_MISSING_TRADING_RULES);
        assertThat(alerts(published).getFirst().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
    }

    // ------------------------------------------------------- the interception record (FR-RK-08)

    /**
     * A rule that moves the clock while it is being consulted, which is what a wall clock does in live:
     * real time passes between the snapshot and the alert. The gate gets one instant per decision, so
     * both have to carry the snapshot's.
     */
    private static final class ClockMovingRule implements SignalRule {

        private final VirtualClock clock;
        private final long drift;
        private final RiskRejection verdict;

        ClockMovingRule(VirtualClock clock, long drift, RiskRejection verdict) {
            this.clock = clock;
            this.drift = drift;
            this.verdict = verdict;
        }

        @Override
        public String ruleId() {
            return verdict.ruleId();
        }

        @Override
        public RiskRule.Level level() {
            return verdict.level();
        }

        @Override
        public Optional<RiskRejection> check(SignalFacts facts) {
            clock.advanceTo(clock.nowMillis() + drift);
            return Optional.of(verdict);
        }
    }

    /** The one row this refusal wrote. Every case below refuses exactly once, so exactly one is the point. */
    private static InterceptionRecord onlyRow(Harness harness) {
        List<InterceptionRecord> rows = harness.records().interceptions(Long.MIN_VALUE, Long.MAX_VALUE);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @Test
    void theRecordIsWrittenBeforeTheAlertThatAnnouncesIt() {
        Harness harness = harness("50", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));
        List<Integer> rowsVisibleWhenTheAlertArrived = new ArrayList<>();

        // Read from inside the publisher rather than after the call. Afterwards, both orders look the
        // same, and it is the order that decides whether a consumer reacting to the alert - which is the
        // only consumer that can be relied on to go and look - finds the row it is looking for.
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), event -> {
            published.add(event);
            if (event instanceof RiskAlertEvent) {
                rowsVisibleWhenTheAlertArrived.add(
                        harness.records().interceptions(PositionSizer.RULE_MIN_NOTIONAL).size());
            }
        });

        assertThat(alerts(published)).hasSize(1);
        assertThat(rowsVisibleWhenTheAlertArrived).containsExactly(1);
    }

    @Test
    void aRefusalIsQueryableByTheRuleThatRefusedAndByTheInstantItRefusedAt() {
        Harness harness = harness("50", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        InterceptionRecord record = onlyRow(harness);
        // FR-RK-08's 按规则查询, and the range query whose ends are both inclusive - so the row at exactly
        // T0 is inside a window that starts and ends at T0.
        assertThat(harness.records().interceptions(PositionSizer.RULE_MIN_NOTIONAL)).containsExactly(record);
        assertThat(harness.records().interceptions(T0, T0)).containsExactly(record);
        // A rule that did not refuse and a window it did not refuse in both find nothing: "none" is an
        // empty list, never null and never every row.
        assertThat(harness.records().interceptions(AccountRule.RULE_ID)).isEmpty();
        assertThat(harness.records().interceptions(T0 + 1, T0 + 2)).isEmpty();
    }

    @Test
    void theAlertAndTheRowCarryTheSameInstantEvenWhenAClockMovesMidDecision() {
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        portfolio.mark(BTC, new BigDecimal("100"));
        VirtualClock clock = new VirtualClock(T0);
        InMemoryRecordStore records = new InMemoryRecordStore();
        RiskRejection verdict = rejection("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER);
        // Built by hand rather than through the harness because the rule needs the clock the gate reads.
        RiskGate gate = new RiskGate(portfolio,
                new PositionSizer(new PositionSizer.Policy(new BigDecimal("0.30"))),
                FixedTradingRulesProvider.of(BTC_RULES),
                new RiskPipeline(List.of(new ClockMovingRule(clock, 750, verdict)), List.of()),
                clock, records);

        gate.onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        // The rule moved the clock 750ms while it was being consulted, and the snapshot was taken before
        // the pipeline ran. One decision, one instant: a second clock read inside the block path is what
        // would stamp the alert 750ms after the row that explains it, and the two would then disagree
        // about when the refusal happened - which is the pairing every query below relies on.
        assertThat(clock.nowMillis()).isEqualTo(T0 + 750);
        RiskAlertEvent alert = alerts(published).getFirst();
        InterceptionRecord record = records.interceptions("RK-05-daily-loss").getFirst();
        assertThat(alert.timestamp()).isEqualTo(T0);
        assertThat(record.timestamp()).isEqualTo(T0);
        assertThat(record.facts().nowMillis()).isEqualTo(T0);
    }

    @Test
    void theHardStopRecordsTheAccountItRefusedAgainst() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(ETH, new BigDecimal("3000"));

        harness.gate().onEvent(signal("ma-cross-eth", ETH, Direction.LONG, 1.0), published::add);

        InterceptionRecord record = onlyRow(harness);
        assertThat(record.ruleId()).isEqualTo(RiskGate.RULE_MISSING_TRADING_RULES);
        assertThat(record.rejection().level()).isEqualTo(RiskRule.Level.SIZING);
        assertThat(record.rejection().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        // The reason the snapshot is taken before step 1 rather than step 2. This refusal is about cached
        // precision and says nothing about the account, so a gate that snapshotted later would write a
        // rejection with no facts beside it - and "what did the account look like" is the only question
        // the row exists to answer.
        assertThat(record.facts().symbol()).isEqualTo(ETH);
        assertThat(record.facts().signal().strategyId()).isEqualTo("ma-cross-eth");
        assertThat(record.facts().equity()).isEqualByComparingTo("10000");
        assertThat(record.facts().price()).isEqualByComparingTo("3000");
        assertThat(record.facts().signedQty()).isEqualByComparingTo("0");
        assertThat(record.timestamp()).isEqualTo(alerts(published).getFirst().timestamp());
    }

    @Test
    void aSizingRejectionIsFiledUnderTheStepThatProducedIt() {
        Harness harness = harness("50", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        InterceptionRecord record = onlyRow(harness);
        // SIZING is FR-RK-07 rather than one of the five policy levels, and the row says so: whoever
        // queries by level is asking which kind of stop this was, and a sizing refusal filed under a
        // policy level would send them hunting a threshold that was never crossed.
        assertThat(record.rejection().level()).isEqualTo(RiskRule.Level.SIZING);
        assertThat(record.ruleId()).isEqualTo(PositionSizer.RULE_MIN_NOTIONAL);
        assertThat(record.rejection().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        // And the account that made it a refusal: 50 of equity at a 30% target cannot reach the 20
        // minimum notional. That arithmetic is only checkable because the row holds the facts.
        assertThat(record.facts().equity()).isEqualByComparingTo("50");
        assertThat(record.facts().price()).isEqualByComparingTo("100");
    }

    @Test
    void anUnpricedSymbolIsRecordedWithTheZeroThatMadeItUnsizeable() {
        Harness harness = harness("10000", BTC_RULES);

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        InterceptionRecord record = onlyRow(harness);
        assertThat(record.ruleId()).isEqualTo(PositionSizer.RULE_NO_PRICE);
        assertThat(record.rejection().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        // SignalFacts.price is never null - markOf falls back to the entry price and then to zero - so
        // the row holds the zero that made this signal unsizeable rather than a hole where the evidence
        // should be. The alert says "no price"; only the row can show the price the gate actually saw.
        assertThat(record.facts().price()).isEqualByComparingTo("0");
        assertThat(record.facts().symbolNotional()).isEqualByComparingTo("0");
    }

    @Test
    void theRowKeepsTheSnapshotTheRulesWereHandedNotAFreshReadingOfTheBook() {
        StubSignalRule rule = new StubSignalRule("RK-02-leverage", RiskRule.Level.ACCOUNT,
                rejection("RK-02-leverage", RiskRule.Level.ACCOUNT));
        Harness harness = harness(new RiskPipeline(List.of(rule), List.of()), "10000", BTC_RULES);
        harness.portfolio().applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("30"), BigDecimal.ZERO);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);
        InterceptionRecord record = onlyRow(harness);
        assertThat(record.facts().price()).isEqualByComparingTo("100");
        assertThat(record.facts().symbolNotional()).isEqualByComparingTo("3000");

        // The market moves on and the row must not: it is the account the decision was made against, and
        // a row re-read afterwards is how an interception record quietly starts defending a decision
        // nobody made. Same hazard PositionSnapshot's markPrice exists to close, on the other table.
        harness.portfolio().mark(BTC, new BigDecimal("400"));

        assertThat(record.facts().price()).isEqualByComparingTo("100");
        assertThat(record.facts().symbolNotional()).isEqualByComparingTo("3000");
        // Same object, not merely equal values: the row holds what the rule was handed.
        assertThat(record.facts()).isSameAs(rule.seen.getFirst());
    }

    @Test
    void aSignalThatNeedsNoTradeIsNotARefusalAndWritesNoRow() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), published::add);

        assertThat(published).isEmpty();
        // Nothing was refused, so nothing is recorded. A row per signal would make the table a copy of
        // the signal table, and "how many times did risk say no" would stop having an answer.
        assertThat(harness.records().interceptions(Long.MIN_VALUE, Long.MAX_VALUE)).isEmpty();
    }

    // ------------------------------------------- the replaceable pipeline (T404, FR-RK-09)

    @Test
    void aReloadedPipelineTakesEffectOnTheNextSignalWithoutARestart() {
        Harness harness = harness(RiskPipeline.empty(), "10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));
        SignalEvent signal = signal("ma-cross-btc", BTC, Direction.LONG, 1.0);

        // The permissive pipeline the gate started with lets the 10%-of-equity order through.
        harness.gate().onEvent(signal, published::add);
        assertThat(orders(published)).hasSize(1);

        published.clear();
        harness.gate().reload(new RiskPipeline(List.of(),
                List.of(new OrderLimitsRule(new BigDecimal("0.05"), new BigDecimal("0.02")))));
        harness.gate().onEvent(signal, published::add);

        // The very same signal is now refused, by the new rule: the swap needs no restart and no
        // second registration, which is the whole of FR-RK-09.
        assertThat(orders(published)).isEmpty();
        assertThat(alerts(published)).hasSize(1);
        assertThat(alerts(published).getFirst().ruleId()).isEqualTo(OrderLimitsRule.RULE_ID);
        assertThat(harness.gate().pipeline().orderRules())
                .extracting(RiskRule::ruleId).containsExactly(OrderLimitsRule.RULE_ID);
    }

    @Test
    void aNullReloadIsRefusedAndThePreviousPipelineStaysInForce() {
        Harness harness = harness("10000", BTC_RULES);
        RiskPipeline before = harness.gate().pipeline();

        assertThatThrownBy(() -> harness.gate().reload(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        // A refused swap is not a partial one: the gate still holds exactly the pipeline it had.
        assertThat(harness.gate().pipeline()).isSameAs(before);
    }
}
