package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-RK-03 / T304: the per-order guard's two checks, each from both sides of its boundary, the order
 * they run in, and what the notional cap deliberately exempts (a reducing order).
 *
 * <p>Numbers are chosen against DESIGN §8 (cap 20%, deviation 2%) with equity 10000 and mark 100, so
 * the notional cap lands on {@code orderNotional > 2000} and the deviation band on {@code |limit - 100|
 * > 2}. Because a MARKET order carries no price, every MARKET case here also proves the deviation check
 * is skipped when there is nothing to compare.
 */
class OrderLimitsRuleTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    private static final BigDecimal MARK = new BigDecimal("100");

    /** DESIGN §8 defaults: 20% single-order cap, 2% fat-finger band. */
    private final OrderLimitsRule rule = new OrderLimitsRule(new BigDecimal("0.20"), new BigDecimal("0.02"));

    /** The rule reads equity, the mark and the current signed qty; totalNotional/symbolNotional are filler. */
    private static SignalFacts book(String signedQty) {
        SignalEvent signal = SignalEvent.of("ma-cross-btc", BTC, Direction.LONG, 1.0, "test", T0);
        BigDecimal sq = new BigDecimal(signedQty);
        BigDecimal notional = Money.of(sq.abs().multiply(MARK));
        return new SignalFacts(signal, EQUITY, EQUITY, notional, notional, sq, MARK, T0);
    }

    private static OrderFacts market(String signedQty, Side side, String qty) {
        return OrderFacts.of(book(signedQty), side, new BigDecimal(qty), null);
    }

    private static OrderFacts limit(String signedQty, Side side, String qty, String limitPrice) {
        return OrderFacts.of(book(signedQty), side, new BigDecimal(qty), new BigDecimal(limitPrice));
    }

    // ------------------------------------------------------------------ notional cap, both sides

    @Test
    void aMarketOrderWithinTheCapPassesAndHasNoPriceToDeviate() {
        // notional 1000 < cap 2000, and limitPrice null so the deviation check is skipped entirely.
        assertThat(rule.check(market("0", Side.BUY, "10"))).isEmpty();
    }

    @Test
    void notionalExactlyAtTheCapPasses() {
        // A full flip is one order of 2 x targetExposure (取舍 17); at the shipped 20% cap that is exactly
        // 2000, so the boundary must be inclusive or every flip would be blocked.
        assertThat(rule.check(market("0", Side.BUY, "20"))).isEmpty();
    }

    @Test
    void notionalOneUnitOverTheCapIsRefusedAsAWarning() {
        Optional<RiskRejection> rejection = rule.check(market("0", Side.BUY, "20.01")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(OrderLimitsRule.RULE_ID);
        assertThat(rejection.get().level()).isEqualTo(RiskRule.Level.ORDER);
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("single-order cap");
    }

    // ------------------------------------------------------------------ deviation band, both sides

    @Test
    void aLimitOrderWithinTheBandPasses() {
        // |101 - 100| = 1 <= 2, and notional 1000 is within the cap.
        assertThat(rule.check(limit("0", Side.BUY, "10", "101"))).isEmpty();
    }

    @Test
    void limitPriceExactlyAtTheBandPassesOnBothSides() {
        assertThat(rule.check(limit("0", Side.BUY, "10", "102"))).isEmpty();
        assertThat(rule.check(limit("0", Side.BUY, "10", "98"))).isEmpty();
    }

    @Test
    void limitPriceBeyondTheBandIsRefusedAsCriticalOnBothSides() {
        Optional<RiskRejection> above = rule.check(limit("0", Side.BUY, "10", "102.01")).stream().findFirst();
        assertThat(above).isPresent();
        assertThat(above.get().ruleId()).isEqualTo(OrderLimitsRule.RULE_ID);
        assertThat(above.get().level()).isEqualTo(RiskRule.Level.ORDER);
        assertThat(above.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(above.get().detail()).contains("fat-finger");

        Optional<RiskRejection> below = rule.check(limit("0", Side.SELL, "10", "97.99")).stream().findFirst();
        assertThat(below).isPresent();
        assertThat(below.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(below.get().detail()).contains("fat-finger");
    }

    // ------------------------------------------------------------------ the two checks' order

    @Test
    void deviationIsCheckedFirstSoACriticalPriceIsNotMaskedByAWarningSize() {
        // limit 110 is 10 off the mark (CRITICAL) and notional 3000 is over the cap (WARNING); the order
        // must report the more severe price objection, which only happens if deviation is checked first.
        Optional<RiskRejection> rejection = rule.check(limit("0", Side.BUY, "30", "110")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(rejection.get().detail()).contains("fat-finger");
    }

    // ------------------------------------------------------------------ the notional cap exempts reductions

    @Test
    void aClosingOrderLargerThanTheCapIsExemptAsAReduction() {
        // Long 30 at mark 100 is a 3000-notional position that appreciated past the 2000 cap; closing it
        // is one 3000-notional order. Blocking it would trap the position, so a reduction is exempt.
        assertThat(rule.check(market("30", Side.SELL, "30"))).isEmpty();
    }

    @Test
    void aPartialReductionIsExemptToo() {
        // Projected |5| < current |30|, so it shrinks the book even though it leaves a position open.
        assertThat(rule.check(market("30", Side.SELL, "25"))).isEmpty();
    }

    @Test
    void aShortPositionClosingIsExemptTheSameWay() {
        // The reduction test is on magnitude, not side: covering a short is de-risking too.
        assertThat(rule.check(market("-30", Side.BUY, "30"))).isEmpty();
    }

    @Test
    void aFlipToALargerMagnitudeIsNotAReductionAndIsCapped() {
        // Long 10, sell 40 -> short 30: projected |30| > current |10|, so it takes on new exposure and
        // the 4000-notional order is refused rather than treated as a close.
        Optional<RiskRejection> rejection = rule.check(market("10", Side.SELL, "40")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("single-order cap");
    }

    @Test
    void aFlipToTheSameMagnitudeIsNotAReduction() {
        // Long 20, sell 40 -> short 20: |20| is not strictly less than |20|, so the exemption does not
        // apply and the 4000-notional order is capped. Only a strict shrink counts as de-risking.
        Optional<RiskRejection> rejection = rule.check(market("20", Side.SELL, "40")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().detail()).contains("single-order cap");
    }

    // ------------------------------------------------------------------ identity and construction guards

    @Test
    void exposesItsIdAndLevel() {
        assertThat(rule.ruleId()).isEqualTo("RK-03-order");
        assertThat(rule.level()).isEqualTo(RiskRule.Level.ORDER);
    }

    @Test
    void refusesANonPositiveNotionalCap() {
        assertThatThrownBy(() -> new OrderLimitsRule(BigDecimal.ZERO, new BigDecimal("0.02")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalFraction");
        assertThatThrownBy(() -> new OrderLimitsRule(new BigDecimal("-0.2"), new BigDecimal("0.02")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalFraction");
        assertThatThrownBy(() -> new OrderLimitsRule(null, new BigDecimal("0.02")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxNotionalFraction");
    }

    @Test
    void refusesANonPositiveDeviationBand() {
        assertThatThrownBy(() -> new OrderLimitsRule(new BigDecimal("0.20"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPriceDeviation");
        assertThatThrownBy(() -> new OrderLimitsRule(new BigDecimal("0.20"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxPriceDeviation");
    }
}
