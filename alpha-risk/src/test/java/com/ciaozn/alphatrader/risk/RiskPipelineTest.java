package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-RK-01's mechanics, without any real rule in sight: order, short-circuiting, the two stages,
 * and the two construction errors that would otherwise surface as a misattributed alert.
 *
 * <p>The rules here are stubs on purpose. What a pipeline does with a rejection is the same
 * whatever produced it, and testing that with the five real levels would mean rebuilding an
 * account state per case to make one of them fire.
 */
class RiskPipelineTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    /** Records what it was handed and returns a fixed verdict. */
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
        return new RiskRejection(ruleId, level, RiskAlertEvent.Severity.WARNING, "stub");
    }

    private static SignalFacts facts() {
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        portfolio.mark(BTC, new BigDecimal("100"));
        return SignalFacts.of(SignalEvent.of("ma-btc", BTC, Direction.LONG, 1.0, "test", T0),
                portfolio, T0);
    }

    private static OrderFacts orderFacts() {
        return OrderFacts.of(facts(), Side.BUY, new BigDecimal("30"));
    }

    // ------------------------------------------------------------------ ordering

    @Test
    void rulesRunInConfiguredOrderNotInLevelOrIdOrder() {
        StubSignalRule first = new StubSignalRule("z-last-alphabetically", RiskRule.Level.FREQUENCY, null);
        StubSignalRule second = new StubSignalRule("a-first-alphabetically", RiskRule.Level.ACCOUNT, null);
        RiskPipeline pipeline = new RiskPipeline(List.of(first, second), List.of());

        pipeline.checkSignal(facts());

        assertThat(first.seen).hasSize(1);
        assertThat(second.seen).hasSize(1);
        // Neither level nor id order: FREQUENCY before ACCOUNT, "z" before "a". Sorting either way
        // would make the interception record depend on how a rule happens to be named.
        assertThat(pipeline.signalRules()).containsExactly(first, second);
    }

    @Test
    void theFirstRejectionEndsThePipelineAndLaterRulesNeverSeeTheSignal() {
        StubSignalRule blocking = new StubSignalRule("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER,
                rejection("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER));
        StubSignalRule after = new StubSignalRule("RK-06-order-rate", RiskRule.Level.FREQUENCY, null);

        Optional<RiskRejection> result = new RiskPipeline(List.of(blocking, after), List.of()).checkSignal(facts());

        assertThat(result).contains(rejection("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER));
        assertThat(after.seen).isEmpty();
    }

    @Test
    void aPassingRuleDoesNotStopTheOneBehindIt() {
        StubSignalRule passing = new StubSignalRule("RK-02-leverage", RiskRule.Level.ACCOUNT, null);
        StubSignalRule blocking = new StubSignalRule("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER,
                rejection("RK-05-daily-loss", RiskRule.Level.CIRCUIT_BREAKER));

        Optional<RiskRejection> result = new RiskPipeline(List.of(passing, blocking), List.of()).checkSignal(facts());

        assertThat(passing.seen).hasSize(1);
        assertThat(result).hasValueSatisfying(r -> assertThat(r.ruleId()).isEqualTo("RK-05-daily-loss"));
    }

    @Test
    void theTwoStagesAreIndependentListsAndNeitherSeesTheOther() {
        StubSignalRule signal = new StubSignalRule("RK-02-leverage", RiskRule.Level.ACCOUNT, null);
        StubOrderRule order = new StubOrderRule("RK-03-order-notional", RiskRule.Level.ORDER, null);
        RiskPipeline pipeline = new RiskPipeline(List.of(signal), List.of(order));

        assertThat(pipeline.checkSignal(facts())).isEmpty();
        assertThat(order.seen).isEmpty();
        assertThat(signal.seen).hasSize(1);

        assertThat(pipeline.checkOrder(orderFacts())).isEmpty();
        // One call each, and neither stage reached into the other's list.
        assertThat(signal.seen).hasSize(1);
        assertThat(order.seen).hasSize(1);
    }

    @Test
    void anEmptyPipelineObjectsToNothing() {
        RiskPipeline pipeline = RiskPipeline.empty();

        assertThat(pipeline.checkSignal(facts())).isEmpty();
        assertThat(pipeline.checkOrder(orderFacts())).isEmpty();
        assertThat(pipeline.signalRules()).isEmpty();
        assertThat(pipeline.orderRules()).isEmpty();
    }

    // ------------------------------------------------------------------ construction errors

    @Test
    void theSameIdInBothStagesIsRefusedBecauseAlertsAreKeyedByIdAlone() {
        StubSignalRule early = new StubSignalRule("RK-04-exposure", RiskRule.Level.PORTFOLIO, null);
        StubOrderRule late = new StubOrderRule("RK-04-exposure", RiskRule.Level.PORTFOLIO, null);

        assertThatThrownBy(() -> new RiskPipeline(List.of(early), List.of(late)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate risk rule id RK-04-exposure");
    }

    @Test
    void aRuleThatRejectsInSomeoneElsesNameIsRefusedLoudly() {
        // Same level, wrong id - the neighbouring test covers the other half, so each comparison in
        // attributed() is the only thing standing between it and a silent pass.
        StubSignalRule confused = new StubSignalRule("RK-02-leverage", RiskRule.Level.ACCOUNT,
                rejection("RK-01-margin-ratio", RiskRule.Level.ACCOUNT));

        assertThatThrownBy(() -> new RiskPipeline(List.of(confused), List.of()).checkSignal(facts()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RK-02-leverage")
                .hasMessageContaining("RK-01-margin-ratio");
    }

    @Test
    void aRuleThatRejectsAtSomeoneElsesLevelIsRefusedLoudly() {
        // Same id, wrong level: the alert would read correctly and the interception record would be
        // filed under a level that never fired.
        StubOrderRule confused = new StubOrderRule("RK-03-order-notional", RiskRule.Level.ORDER,
                rejection("RK-03-order-notional", RiskRule.Level.PORTFOLIO));

        assertThatThrownBy(() -> new RiskPipeline(List.of(), List.of(confused)).checkOrder(orderFacts()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("level ORDER")
                .hasMessageContaining("level PORTFOLIO");
    }

    @Test
    void aRejectionWithoutAnIdOrLevelCannotBeBuilt() {
        assertThatThrownBy(() -> new RiskRejection(" ", RiskRule.Level.ORDER,
                RiskAlertEvent.Severity.WARNING, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name the rule");
        assertThatThrownBy(() -> new RiskRejection("RK-03-x", null,
                RiskAlertEvent.Severity.WARNING, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry a level");
        assertThatThrownBy(() -> new RiskRejection("RK-03-x", RiskRule.Level.ORDER, null, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry a severity");
    }

    @Test
    void theRuleListsCannotBeModifiedAfterConstruction() {
        List<SignalRule> rules = new ArrayList<>();
        rules.add(new StubSignalRule("RK-02-leverage", RiskRule.Level.ACCOUNT, null));
        RiskPipeline pipeline = new RiskPipeline(rules, List.of());
        rules.clear();

        assertThat(pipeline.signalRules()).hasSize(1);
        assertThatThrownBy(() -> pipeline.signalRules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ facts

    @Test
    void theOrderFactsProjectTheBookAsItWouldBeAfterTheFill() {
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        portfolio.mark(BTC, new BigDecimal("100"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO);
        SignalFacts facts = SignalFacts.of(SignalEvent.of("ma-btc", BTC, Direction.LONG, 1.0, "test", T0),
                portfolio, T0);

        assertThat(facts.signedQty()).isEqualByComparingTo("10");
        assertThat(facts.symbolNotional()).isEqualByComparingTo("1000");
        assertThat(facts.totalNotional()).isEqualByComparingTo("1000");

        OrderFacts adding = OrderFacts.of(facts, Side.BUY, new BigDecimal("30"));
        assertThat(adding.orderNotional()).isEqualByComparingTo("3000");
        assertThat(adding.projectedSignedQty()).isEqualByComparingTo("40");
        assertThat(adding.projectedSymbolNotional()).isEqualByComparingTo("4000");
        assertThat(adding.projectedTotalNotional()).isEqualByComparingTo("4000");

        // A flip: the projection has to cross zero, not clamp at it.
        OrderFacts flipping = OrderFacts.of(facts, Side.SELL, new BigDecimal("30"));
        assertThat(flipping.projectedSignedQty()).isEqualByComparingTo("-20");
        assertThat(flipping.projectedSymbolNotional()).isEqualByComparingTo("2000");
        assertThat(flipping.projectedTotalNotional()).isEqualByComparingTo("2000");
    }

    @Test
    void theProjectionUsesTheSignedQuantitySoAShortBookIsNotReadAsALongOne() {
        // Position.qty() is absolute and signedQty() carries the sign, so a short book is the only
        // place the two differ - and the only place a rule can be handed the wrong one.
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        portfolio.mark(BTC, new BigDecimal("100"));
        portfolio.applyFill(BTC, Side.SELL, new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO);
        SignalFacts facts = SignalFacts.of(SignalEvent.of("ma-btc", BTC, Direction.SHORT, 1.0, "test", T0),
                portfolio, T0);

        assertThat(facts.signedQty()).isEqualByComparingTo("-10");
        // Exposure is a magnitude: a short of 10 at 100 ties up the same notional as a long.
        assertThat(facts.symbolNotional()).isEqualByComparingTo("1000");
        assertThat(facts.totalNotional()).isEqualByComparingTo("1000");

        // Covering and flipping: -10 + 30 = +20 long, not 40.
        OrderFacts covering = OrderFacts.of(facts, Side.BUY, new BigDecimal("30"));
        assertThat(covering.projectedSignedQty()).isEqualByComparingTo("20");
        assertThat(covering.projectedSymbolNotional()).isEqualByComparingTo("2000");
        assertThat(covering.projectedTotalNotional()).isEqualByComparingTo("2000");

        OrderFacts adding = OrderFacts.of(facts, Side.SELL, new BigDecimal("30"));
        assertThat(adding.projectedSignedQty()).isEqualByComparingTo("-40");
        assertThat(adding.projectedSymbolNotional()).isEqualByComparingTo("4000");
    }

    @Test
    void theProjectedTotalSwapsOnlyTheTradedSymbolsSliceOfTheBook() {
        Symbol eth = Symbol.parse("ETHUSDT.PERP");
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        portfolio.mark(BTC, new BigDecimal("100"));
        portfolio.mark(eth, new BigDecimal("50"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("10"), BigDecimal.ZERO);
        portfolio.applyFill(eth, Side.BUY, new BigDecimal("50"), new BigDecimal("5"), BigDecimal.ZERO);
        SignalFacts facts = SignalFacts.of(SignalEvent.of("ma-btc", BTC, Direction.LONG, 1.0, "test", T0),
                portfolio, T0);

        assertThat(facts.totalNotional()).isEqualByComparingTo("1250");

        OrderFacts order = OrderFacts.of(facts, Side.BUY, new BigDecimal("30"));

        // 1250 - BTC's 1000 + the projected 4000: ETH's slice is untouched by a BTC order.
        assertThat(order.projectedSymbolNotional()).isEqualByComparingTo("4000");
        assertThat(order.projectedTotalNotional()).isEqualByComparingTo("4250");
    }

    @Test
    void theFactsCarryTheAccountSnapshotTheInterceptionRecordWillQuote() {
        Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
        portfolio.mark(BTC, new BigDecimal("100"));

        SignalFacts facts = SignalFacts.of(SignalEvent.of("ma-btc", BTC, Direction.LONG, 1.0, "test", T0),
                portfolio, T0);

        assertThat(facts.equity()).isEqualByComparingTo("10000");
        assertThat(facts.cash()).isEqualByComparingTo("10000");
        assertThat(facts.price()).isEqualByComparingTo("100");
        assertThat(facts.nowMillis()).isEqualTo(T0);
        assertThat(facts.symbol()).isEqualTo(BTC);
    }

    @Test
    void anUnmarkedSymbolHasAZeroPriceRatherThanNoPriceAtAll() {
        // Portfolio falls back to the entry price and then to zero, so the facts are never null -
        // which is why saying "this price is unusable" is FR-RK-07's job, not a rule's.
        SignalFacts facts = SignalFacts.of(SignalEvent.of("ma-btc", BTC, Direction.LONG, 1.0, "test", T0),
                new Portfolio(new BigDecimal("10000")), T0);

        assertThat(facts.price()).isEqualByComparingTo("0");
        assertThat(facts.symbolNotional()).isEqualByComparingTo("0");
    }
}
