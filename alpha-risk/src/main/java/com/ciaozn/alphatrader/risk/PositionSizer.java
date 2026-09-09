package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.TradingRules;

import java.math.BigDecimal;

/**
 * Turns signal intent into a concrete quantity (FR-RK-07): the strategy says "go long",
 * this decides how much - equity x target exposure x signal strength / price - and rounds it
 * down to the exchange's stepSize.
 *
 * <p>Rounding is always downward and anything that ends up below the exchange minimum is
 * REJECTED with an alert rather than sent (spec edge case 4). A rejected order costs a missed
 * fraction of a position; a dirty order costs a rejection at best and an unintended exposure
 * at worst.
 *
 * <p>Stateless: all inputs are arguments, so the same call in backtest and live produces the
 * same quantity (FR-BT-06) and the sizing can be unit-tested without an engine.
 *
 * <p>Quantity is in base asset (linear USDⓈ-M perpetuals, contract multiplier 1).
 */
public final class PositionSizer {

    public static final String RULE_MIN_QTY = "RK-07-min-quantity";
    public static final String RULE_MIN_NOTIONAL = "RK-07-min-notional";
    public static final String RULE_STRENGTH = "RK-07-signal-strength";
    public static final String RULE_NO_PRICE = "RK-07-no-price";
    public static final String RULE_NO_EQUITY = "RK-07-no-equity";

    /**
     * @param targetExposure fraction of equity one symbol should hold at full signal strength,
     *                       e.g. 0.10 for 10%. Capped at 1.0 on purpose: leverage is the
     *                       account-level rule's decision (FR-RK-02, implemented in P3), and
     *                       until that rule exists the sizer must not be able to overshoot the
     *                       account on its own.
     */
    public record Policy(BigDecimal targetExposure) {

        /**
         * 0.10, and the number is coupled to the order-level cap: a flip is one order of
         * 2 x targetExposure (close the long, open the short), so with DESIGN §8's 单笔 ≤20% 权益 a
         * larger exposure would have every flip and every entry-to-target blocked by FR-RK-03 - the
         * account would keep holding a position the strategy had already reversed out of, with only
         * an alert to show for it. {@code AlphaProperties.Risk} refuses to start on a configuration
         * where {@code order.max-notional-fraction < 2 x targetExposure}.
         */
        public static final Policy DEFAULT = new Policy(new BigDecimal("0.10"));

        public Policy {
            if (targetExposure == null || targetExposure.signum() <= 0
                    || targetExposure.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("targetExposure must be in (0, 1], got " + targetExposure);
            }
        }
    }

    /** Outcome of one sizing attempt: trade, do nothing, or block with a reason to alert on. */
    public sealed interface Result {

        /** Target position already equals the current one - not an error, nothing to send. */
        record NoTrade() implements Result {
        }

        /** Quantity is aligned to stepSize and above the exchange minimums. */
        record Order(Side side, BigDecimal qty) implements Result {
        }

        /** Blocked. Carries everything {@code RiskAlertEvent} needs (FR-RK-08). */
        record Rejected(String ruleId, RiskAlertEvent.Severity severity, String detail) implements Result {
        }
    }

    private final Policy policy;

    public PositionSizer(Policy policy) {
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }

    /**
     * @param strength         signal strength in [0, 1]; scales the target exposure
     * @param equity           account equity (cash + unrealized) to size against
     * @param currentSignedQty signed quantity currently held: positive long, negative short
     * @param price            mark price used for both the quantity and the minimum-notional check
     */
    public Result size(Direction direction, double strength, BigDecimal equity,
                       BigDecimal currentSignedQty, BigDecimal price, TradingRules rules) {
        if (price == null || price.signum() <= 0) {
            return new Result.Rejected(RULE_NO_PRICE, RiskAlertEvent.Severity.CRITICAL,
                    "no usable mark price for " + rules.symbol().unified() + " (" + price + ")");
        }
        if (equity == null || equity.signum() <= 0) {
            return new Result.Rejected(RULE_NO_EQUITY, RiskAlertEvent.Severity.CRITICAL,
                    "equity is " + equity + ", nothing to size a position with");
        }
        if (!(strength >= 0 && strength <= 1)) {
            return new Result.Rejected(RULE_STRENGTH, RiskAlertEvent.Severity.WARNING,
                    "signal strength " + strength + " is outside [0, 1]");
        }
        if (strength == 0) {
            return new Result.NoTrade();
        }

        BigDecimal delta = targetSignedQty(direction, equity, strength, price).subtract(currentSignedQty);
        if (delta.signum() == 0) {
            return new Result.NoTrade();
        }
        BigDecimal qty = rules.floorQty(delta.abs());
        if (qty.signum() == 0) {
            return new Result.Rejected(RULE_MIN_QTY, RiskAlertEvent.Severity.WARNING,
                    "delta " + delta.toPlainString() + " rounds to zero at stepSize "
                            + rules.stepSize().toPlainString() + ", no order sent");
        }
        if (!rules.meetsMinNotional(qty, price)) {
            return new Result.Rejected(RULE_MIN_NOTIONAL, RiskAlertEvent.Severity.WARNING,
                    "qty " + qty.toPlainString() + " at price " + price.toPlainString()
                            + " is below minNotional " + rules.minNotional().toPlainString() + ", no order sent");
        }
        return new Result.Order(delta.signum() > 0 ? Side.BUY : Side.SELL, qty);
    }

    private BigDecimal targetSignedQty(Direction direction, BigDecimal equity, double strength, BigDecimal price) {
        if (direction == Direction.FLAT) {
            return Money.zero();
        }
        BigDecimal notional = Money.of(equity
                .multiply(policy.targetExposure(), Money.MC)
                .multiply(BigDecimal.valueOf(strength), Money.MC));
        BigDecimal qty = Money.divide(notional, price);
        return direction == Direction.LONG ? qty : qty.negate();
    }
}
