package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.risk.AccountRule;
import com.ciaozn.alphatrader.risk.CircuitBreaker;
import com.ciaozn.alphatrader.risk.FrequencyRule;
import com.ciaozn.alphatrader.risk.OrderLimitsRule;
import com.ciaozn.alphatrader.risk.PortfolioRule;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.RiskRejection;
import com.ciaozn.alphatrader.risk.RiskRule;
import com.ciaozn.alphatrader.risk.SignalFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T308: the one mapping from {@code alpha.risk.*} to a rule set, which is what makes FR-BT-06 hold
 * for the rules and not only for the classes - three wirings each translating the same yml is three
 * chances for a backtest to run a rule set the live system does not.
 *
 * <p>This pins <em>which rules, in which stage, in which order</em>, and nothing about their
 * arithmetic. The numbers are pinned from the other side: {@code RiskPropertiesTest} holds the
 * shipped yml and {@code AlphaProperties.Risk.DEFAULTS} against DESIGN §8, and alpha-risk's own tests
 * (with {@code RiskInterceptionMatrixTest} for the assembled gate) hold each rule's verdicts against
 * the same section. Chained, that is yml -> §8 -> rule set -> verdict, with no two of them compared
 * to each other - so a change to any one link fails somewhere, and the arithmetic is not retyped here
 * where a copy could drift from the rule it describes.
 *
 * <p>Order is asserted rather than left to chance because a rule is reached only if every rule before
 * it passed, so the order decides which rule a blocked signal is <em>attributed</em> to. Two levels
 * that both object to one signal produce one alert, and its rule id is the only thing an operator has
 * to go on.
 */
class RiskPipelinesTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final BigDecimal CASH = new BigDecimal("10000");
    private static final long T0 = 1_700_000_000_000L;

    // ------------------------------------------------------------------ the mapping

    @Test
    void theShippedRiskBecomesFiveRulesInTwoStagesInTheContractedOrder() {
        RiskPipeline pipeline = RiskPipelines.of(AlphaProperties.Risk.DEFAULTS, new Portfolio(CASH));

        // Signal stage: account (the hardest and cheapest stop), then the breaker (a day the breaker
        // has closed should not spend the rate budget on refusals), then frequency last so its window
        // counts only the signals the two above let through.
        assertThat(pipeline.signalRules()).extracting(RiskRule::ruleId)
                .containsExactly(AccountRule.RULE_ID, CircuitBreaker.RULE_ID, FrequencyRule.RULE_ID);
        // Order stage: the per-order checks before the book-wide ones, so an order that is both
        // mispriced and oversized reports the mispricing.
        assertThat(pipeline.orderRules()).extracting(RiskRule::ruleId)
                .containsExactly(OrderLimitsRule.RULE_ID, PortfolioRule.RULE_ID);

        // The stage split is the other half of the order: a rule in the wrong stage is either handed a
        // quantity it must not use or asked for one that does not exist yet (see RiskRule).
        assertThat(pipeline.signalRules()).extracting(RiskRule::level)
                .containsExactly(RiskRule.Level.ACCOUNT, RiskRule.Level.CIRCUIT_BREAKER,
                        RiskRule.Level.FREQUENCY);
        assertThat(pipeline.orderRules()).extracting(RiskRule::level)
                .containsExactly(RiskRule.Level.ORDER, RiskRule.Level.PORTFOLIO);

        // And the class behind each id: an id is a string, so a stub or a second implementation
        // reporting the right one would pass every assertion above while deciding by other rules.
        assertThat(pipeline.signalRules())
                .hasExactlyElementsOfTypes(AccountRule.class, CircuitBreaker.class, FrequencyRule.class);
        assertThat(pipeline.orderRules())
                .hasExactlyElementsOfTypes(OrderLimitsRule.class, PortfolioRule.class);
    }

    @Test
    void exactlyTheBreakerWatchesTheBusAndItIsFoundBySweepingThePipeline() {
        RiskPipeline pipeline = RiskPipelines.of(AlphaProperties.Risk.DEFAULTS, new Portfolio(CASH));

        // BacktestRunner.registerRuleObservers sweeps both stages for EventHandlers rather than naming
        // the breaker, so this is the seam that decides whether the breaker's losing-streak trigger
        // ever learns about a fill. A second watcher would be registered too and would see every event
        // the gate sees; none at all would leave a breaker that is consulted, looks configured, and
        // counts nothing.
        List<RiskRule> watchers = Stream
                .concat(pipeline.signalRules().stream(), pipeline.orderRules().stream())
                .filter(EventHandler.class::isInstance)
                .map(RiskRule.class::cast)
                .toList();
        assertThat(watchers).containsExactly(pipeline.signalRules().get(1));
        assertThat(watchers.getFirst().ruleId()).isEqualTo(CircuitBreaker.RULE_ID);
    }

    /**
     * The reason {@code BacktestRunner} takes a {@code RiskPipelineFactory} rather than a pipeline:
     * the breaker reads equity and realized P&L off the run's book, and that book does not exist until
     * {@code run()} creates it. A breaker built around any other {@link Portfolio} - a fresh one, or a
     * previous run's - decides from a book nobody updates, so its losing streak never advances and its
     * daily-loss latch never trips. Nothing else in the suite can see that: the other four rules never
     * touch the book they were not handed, and the gate's own verdicts come from {@code SignalFacts}.
     */
    @Test
    void theBreakerDecidesFromTheBookItWasHanded() {
        Portfolio book = new Portfolio(CASH);
        book.mark(BTC, new BigDecimal("100"));
        CircuitBreaker breaker = (CircuitBreaker) RiskPipelines
                .of(AlphaProperties.Risk.DEFAULTS, book).signalRules().get(1);

        // Three losing round trips, each applied to the caller's book before the breaker is told -
        // 取舍 16 is what makes the realized P&L delta readable at all. The openings in between
        // realize zero and must not reset the streak.
        fill(breaker, book, Side.BUY, "100", "10");
        fill(breaker, book, Side.SELL, "99", "10");
        fill(breaker, book, Side.BUY, "99", "10");
        fill(breaker, book, Side.SELL, "98", "10");
        fill(breaker, book, Side.BUY, "98", "10");
        fill(breaker, book, Side.SELL, "97", "10");

        Optional<RiskRejection> rejection = breaker.check(SignalFacts.of(
                SignalEvent.of("ma-cross-btc", BTC, Direction.LONG, 1.0, "test", T0), book, T0));

        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(CircuitBreaker.RULE_ID);
        // The WARNING losing-streak trigger, not the CRITICAL daily one: 30 of loss against a 500
        // threshold, so the pause is what fired.
        assertThat(rejection.get().detail()).contains("consecutive losing trades");
    }

    private static void fill(CircuitBreaker breaker, Portfolio book, Side side, String price, String qty) {
        BigDecimal fillPrice = new BigDecimal(price);
        BigDecimal fillQty = new BigDecimal(qty);
        book.applyFill(BTC, side, fillPrice, fillQty, BigDecimal.ZERO);
        breaker.onFill(FillEvent.of("fill", BTC, side, fillPrice, fillQty, BigDecimal.ZERO, T0));
    }

    // ------------------------------------------------------------------ a level switched off

    @Test
    void aLevelSwitchedOffIsAbsentFromThePipelineRatherThanPresentAndPermissive() {
        // The nested blocks still validate when disabled - an off breaker with consecutive-losses 0 is
        // a configuration that would break the moment someone switched it back on - so the off cases
        // carry legal numbers and only the flag moves.
        Map<String, AlphaProperties.Risk> offByRuleId = new LinkedHashMap<>();
        offByRuleId.put(AccountRule.RULE_ID, new AlphaProperties.Risk(
                new AlphaProperties.Risk.Account(false, null, null), null, null, null, null, null));
        offByRuleId.put(OrderLimitsRule.RULE_ID, new AlphaProperties.Risk(
                null, new AlphaProperties.Risk.Order(false, null, null), null, null, null, null));
        offByRuleId.put(PortfolioRule.RULE_ID, new AlphaProperties.Risk(
                null, null, new AlphaProperties.Risk.Portfolio(false, null, null), null, null, null));
        offByRuleId.put(CircuitBreaker.RULE_ID, new AlphaProperties.Risk(
                null, null, null,
                new AlphaProperties.Risk.Breaker(false, null, 3, null), null, null));
        offByRuleId.put(FrequencyRule.RULE_ID, new AlphaProperties.Risk(
                null, null, null, null,
                new AlphaProperties.Risk.Frequency(false, 10, null), null));

        assertThat(offByRuleId).hasSize(5);
        offByRuleId.forEach((ruleId, risk) -> {
            List<String> ids = ruleIds(RiskPipelines.of(risk, new Portfolio(CASH)));
            // Absent, so the id can never appear in an alert or an interception record: the honest
            // reading of "this level is off". A rule that always passes would still be listed and
            // still look enforced.
            assertThat(ids).as("%s switched off", ruleId).doesNotContain(ruleId);
            // And the other four still stand - switching one level off is not switching the gate off.
            assertThat(ids).as("%s switched off", ruleId).hasSize(4);
        });
    }

    @Test
    void everyLevelSwitchedOffLeavesAnEmptyPipelineRatherThanNoPipeline() {
        RiskPipeline pipeline = RiskPipelines.of(new AlphaProperties.Risk(
                new AlphaProperties.Risk.Account(false, null, null),
                new AlphaProperties.Risk.Order(false, null, null),
                new AlphaProperties.Risk.Portfolio(false, null, null),
                new AlphaProperties.Risk.Breaker(false, null, 3, null),
                new AlphaProperties.Risk.Frequency(false, 10, null),
                null), new Portfolio(CASH));

        // Empty is a legal pipeline meaning "no rules beyond sizing" (RiskPipeline.empty): the gate
        // still refuses a symbol with no cached trading rules and still rejects a quantity that rounds
        // below the exchange minimum. A null here would be a NullPointerException at the first signal.
        assertThat(pipeline.signalRules()).isEmpty();
        assertThat(pipeline.orderRules()).isEmpty();
        assertThat(ruleIds(pipeline)).isEmpty();
    }

    private static List<String> ruleIds(RiskPipeline pipeline) {
        return Stream.concat(pipeline.signalRules().stream(), pipeline.orderRules().stream())
                .map(RiskRule::ruleId)
                .toList();
    }
}
