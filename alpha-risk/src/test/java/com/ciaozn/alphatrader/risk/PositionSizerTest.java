package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-RK-07 (sizing) plus spec edge case 4 (below-minimum quantity must never become an order).
 * Quantity scale is asserted exactly, not just by value: the exchange filter check downstream
 * compares scales too, and determinism (NFR-04) means the same call must produce the same digits.
 */
class PositionSizerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final TradingRules BTC_RULES =
            new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("20"));

    // 0.30 by name rather than Policy.DEFAULT: every quantity below is hand-computed against it,
    // and the default is a configuration number that moves when DESIGN §8's order cap does. The
    // default's own value is pinned in defaultsTargetExposureLeavesRoomForAFlip.
    private final PositionSizer sizer = new PositionSizer(new PositionSizer.Policy(new BigDecimal("0.30")));

    private PositionSizer.Result size(Direction direction, double strength, String equity,
                                      String held, String price) {
        return size(direction, strength, equity, held, price, BTC_RULES);
    }

    private PositionSizer.Result size(Direction direction, double strength, String equity,
                                      String held, String price, TradingRules rules) {
        return sizer.size(direction, strength, new BigDecimal(equity), new BigDecimal(held),
                new BigDecimal(price), rules);
    }

    private static void assertOrder(PositionSizer.Result result, Side side, String qty) {
        assertThat(result).isInstanceOf(PositionSizer.Result.Order.class);
        PositionSizer.Result.Order order = (PositionSizer.Result.Order) result;
        assertThat(order.side()).isEqualTo(side);
        assertThat(order.qty()).isEqualTo(new BigDecimal(qty));
    }

    private static void assertRejected(PositionSizer.Result result, String ruleId,
                                       RiskAlertEvent.Severity severity) {
        assertThat(result).isInstanceOf(PositionSizer.Result.Rejected.class);
        PositionSizer.Result.Rejected rejected = (PositionSizer.Result.Rejected) result;
        assertThat(rejected.ruleId()).isEqualTo(ruleId);
        assertThat(rejected.severity()).isEqualTo(severity);
        assertThat(rejected.detail()).isNotBlank();
    }

    // ------------------------------------------------------------------ sizing

    @Test
    void sizesALongFromEquityExposureStrengthAndPrice() {
        // 10000 x 0.30 x 1.0 / 100 = 30
        assertOrder(size(Direction.LONG, 1.0, "10000", "0", "100"), Side.BUY, "30.000");
    }

    @Test
    void sizesAShortAsTheSameQuantityOnTheOtherSide() {
        assertOrder(size(Direction.SHORT, 1.0, "10000", "0", "100"), Side.SELL, "30.000");
    }

    @Test
    void signalStrengthScalesTheTargetExposure() {
        assertOrder(size(Direction.LONG, 0.5, "10000", "0", "100"), Side.BUY, "15.000");
    }

    @Test
    void flatteningSellsExactlyWhatIsHeld() {
        assertOrder(size(Direction.FLAT, 1.0, "10000", "30", "100"), Side.SELL, "30.000");
    }

    @Test
    void ordersOnlyTheDifferenceWhenAlreadyPartlyInvested() {
        // target 15 while holding 30 -> reduce by 15, not open a second 15
        assertOrder(size(Direction.LONG, 0.5, "10000", "30", "100"), Side.SELL, "15.000");
    }

    @Test
    void flippingFromShortToLongCoversThenOpens() {
        // target +30 while holding -30 -> buy 60 in one order
        assertOrder(size(Direction.LONG, 1.0, "10000", "-30", "100"), Side.BUY, "60.000");
    }

    @Test
    void roundsDownToTheExchangeStepSize() {
        TradingRules coarse = new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("7"),
                new BigDecimal("20"));
        // 30 / 7 = 4 steps -> 28, never 35
        assertOrder(size(Direction.LONG, 1.0, "10000", "0", "100", coarse), Side.BUY, "28");
    }

    @Test
    void noTradeWhenTheTargetAlreadyEqualsThePosition() {
        assertThat(size(Direction.LONG, 1.0, "10000", "30", "100"))
                .isEqualTo(new PositionSizer.Result.NoTrade());
        assertThat(size(Direction.FLAT, 1.0, "10000", "0", "100"))
                .isEqualTo(new PositionSizer.Result.NoTrade());
    }

    @Test
    void noTradeWhenTheSignalCarriesNoConviction() {
        assertThat(size(Direction.LONG, 0.0, "10000", "0", "100"))
                .isEqualTo(new PositionSizer.Result.NoTrade());
    }

    // ------------------------------------------------------------------ spec edge case 4

    @Test
    void rejectsAnOrderBelowTheExchangeMinimumNotional() {
        // 50 x 0.30 / 100 = 0.15 -> 15 USDT, under the 20 USDT minimum: skip and alert
        assertRejected(size(Direction.LONG, 1.0, "50", "0", "100"),
                PositionSizer.RULE_MIN_NOTIONAL, RiskAlertEvent.Severity.WARNING);
    }

    @Test
    void rejectsAQuantityThatRoundsToZero() {
        TradingRules wholeUnits = new TradingRules(BTC, new BigDecimal("0.10"), BigDecimal.ONE,
                new BigDecimal("20"));
        // 10000 x 0.30 / 100000 = 0.03 -> zero whole units
        assertRejected(size(Direction.LONG, 1.0, "10000", "0", "100000", wholeUnits),
                PositionSizer.RULE_MIN_QTY, RiskAlertEvent.Severity.WARNING);
    }

    @Test
    void rejectsADeltaTooSmallToMoveThePosition() {
        TradingRules coarse = new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("7"),
                new BigDecimal("20"));
        // holding 28 of a 30 target leaves a 2-unit delta that one 7-unit step cannot express
        assertRejected(size(Direction.LONG, 1.0, "10000", "28", "100", coarse),
                PositionSizer.RULE_MIN_QTY, RiskAlertEvent.Severity.WARNING);
    }

    // ------------------------------------------------------------------ unusable inputs

    @Test
    void rejectsSizingWithoutAMarkPrice() {
        assertRejected(size(Direction.LONG, 1.0, "10000", "0", "0"),
                PositionSizer.RULE_NO_PRICE, RiskAlertEvent.Severity.CRITICAL);
        assertRejected(size(Direction.LONG, 1.0, "10000", "0", "-1"),
                PositionSizer.RULE_NO_PRICE, RiskAlertEvent.Severity.CRITICAL);
    }

    @Test
    void rejectsSizingWithoutEquity() {
        assertRejected(size(Direction.LONG, 1.0, "0", "0", "100"),
                PositionSizer.RULE_NO_EQUITY, RiskAlertEvent.Severity.CRITICAL);
        assertRejected(size(Direction.LONG, 1.0, "-500", "0", "100"),
                PositionSizer.RULE_NO_EQUITY, RiskAlertEvent.Severity.CRITICAL);
    }

    @Test
    void rejectsAStrengthOutsideTheContract() {
        assertRejected(size(Direction.LONG, 1.5, "10000", "0", "100"),
                PositionSizer.RULE_STRENGTH, RiskAlertEvent.Severity.WARNING);
        assertRejected(size(Direction.LONG, -0.1, "10000", "0", "100"),
                PositionSizer.RULE_STRENGTH, RiskAlertEvent.Severity.WARNING);
        assertRejected(size(Direction.LONG, Double.NaN, "10000", "0", "100"),
                PositionSizer.RULE_STRENGTH, RiskAlertEvent.Severity.WARNING);
    }

    // ------------------------------------------------------------------ policy

    @Test
    void targetExposureIsBoundedToTheAccount() {
        assertThatThrownBy(() -> new PositionSizer.Policy(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionSizer.Policy(new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.5");
        assertThatThrownBy(() -> new PositionSizer.Policy(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PositionSizer.Policy(BigDecimal.ONE).targetExposure()).isEqualTo(BigDecimal.ONE);
    }

    @Test
    void aSmallerTargetExposureMeansASmallerOrder() {
        PositionSizer cautious = new PositionSizer(new PositionSizer.Policy(new BigDecimal("0.10")));
        PositionSizer.Result result = cautious.size(Direction.LONG, 1.0, new BigDecimal("10000"),
                BigDecimal.ZERO, new BigDecimal("100"), BTC_RULES);
        assertOrder(result, Side.BUY, "10.000");
        assertThat(cautious.policy().targetExposure()).isEqualByComparingTo("0.10");
    }

    @Test
    void defaultsTargetExposureLeavesRoomForAFlip() {
        // DESIGN §8 caps one order at 20% of equity, and a flip is two orders of the target
        // exposure (cover the long, open the short). At 0.30 the default configuration would have
        // refused every flip and every entry-to-target, which reads as a broken strategy rather
        // than as an over-large default - hence 0.10 here and the same inequality enforced on
        // operator-supplied numbers by AlphaProperties.Risk.
        assertThat(PositionSizer.Policy.DEFAULT.targetExposure()).isEqualByComparingTo("0.10");
        assertThat(PositionSizer.Policy.DEFAULT.targetExposure().multiply(BigDecimal.valueOf(2)))
                .isLessThanOrEqualTo(new BigDecimal("0.20"));
    }
}
