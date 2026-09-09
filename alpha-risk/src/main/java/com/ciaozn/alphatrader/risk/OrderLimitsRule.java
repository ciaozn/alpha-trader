package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Money;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 订单级 (FR-RK-03, DESIGN §8): the per-order guard, run in the order stage - after FR-RK-07 has
 * produced a quantity, so it can object to a concrete order and quote the notional it refused. Two
 * checks under one id:
 * <ul>
 *   <li><b>fat-finger price guard</b>: a LIMIT order whose price is more than {@code maxPriceDeviation}
 *       (2%) away from the mark is refused (CRITICAL). A MARKET order has no price - it executes at the
 *       touch - so there is nothing to deviate and the check skips it. This is checked <em>first</em> so
 *       that an order which is both mispriced and oversized reports the more severe condition instead of
 *       masking a CRITICAL behind a WARNING.</li>
 *   <li><b>single-order notional cap</b>: an order whose notional exceeds {@code maxNotionalFraction}
 *       (20%) of equity is refused (WARNING).</li>
 * </ul>
 *
 * <p><b>Both checks are backstops under the shipped configuration, and that is by design rather than an
 * accident.</b> The notional cap is coupled to the sizer (取舍 17: {@code maxNotionalFraction >= 2 x
 * targetExposure}), and the sizer never asks for more than {@code 2 x targetExposure x equity} (a full
 * flip at strength 1), so a normally-sized order sits at or under the cap - the cap is the enforcement
 * point that makes the coupling a real invariant instead of a hope, and it fires for any order from any
 * future source that exceeds policy. The price guard is dormant while the gate sends MARKET only, and
 * becomes live the moment a LIMIT order is introduced. Neither is "a rule that never runs and is
 * therefore dead": each is correct, unit-tested against an order that violates it, and is the single
 * place that condition is enforced.
 *
 * <p><b>The notional cap exempts orders that shrink the book.</b> A position can grow past 20% of
 * equity by price appreciation alone (the sizer targets 10%, so it never builds that far on purpose),
 * and closing such a position is one order whose notional equals the whole position - over the cap. If
 * the cap blocked it, the account would hold a position it cannot exit, which is the same trap
 * {@link AccountRule} avoids by always passing FLAT. So the cap bounds <em>new</em> notional only: an
 * order whose projected position magnitude is strictly smaller than the current one is reducing
 * exposure and is let through. A flip to the same or a larger magnitude is not a reduction and is still
 * capped. The price guard is <em>not</em> exempted for reductions - closing at a fat-fingered price is
 * just as bad as opening at one.
 *
 * <p>Equity and the mark are both guaranteed usable here: the sizer rejects {@code RULE_NO_EQUITY} and
 * {@code RULE_NO_PRICE} before the order stage runs, so this rule does not re-check them - one owner per
 * condition, the same rule {@link AccountRule} follows for no-equity. Decisions use cross-multiplication
 * rather than division, so they are exact at the boundary (an order exactly at the cap or exactly at the
 * deviation band passes, matching 取舍 17's "a flip is one order of 2 x targetExposure"). Stateless and
 * Spring-free: the same order in backtest and live gives the same verdict (FR-BT-06).
 */
public final class OrderLimitsRule implements OrderRule {

    /** The order level's one id; which of the two checks fired is carried in the detail. */
    public static final String RULE_ID = "RK-03-order";

    private final BigDecimal maxNotionalFraction;
    private final BigDecimal maxPriceDeviation;

    /**
     * @param maxNotionalFraction cap on one order's notional as a fraction of equity, e.g. 0.20 for 20%
     * @param maxPriceDeviation   cap on a LIMIT order's distance from the mark, e.g. 0.02 for 2%
     */
    public OrderLimitsRule(BigDecimal maxNotionalFraction, BigDecimal maxPriceDeviation) {
        if (maxNotionalFraction == null || maxNotionalFraction.signum() <= 0) {
            throw new IllegalArgumentException(
                    "maxNotionalFraction must be positive, got " + maxNotionalFraction
                            + ": a cap at or below zero would refuse every order that is not a reduction,"
                            + " which is trading switched off reporting itself as a stream of risk alerts");
        }
        if (maxPriceDeviation == null || maxPriceDeviation.signum() <= 0) {
            throw new IllegalArgumentException(
                    "maxPriceDeviation must be positive, got " + maxPriceDeviation
                            + ": a band at or below zero would refuse every LIMIT order whose price is not"
                            + " exactly the mark, so the guard could never be satisfied by a real quote");
        }
        this.maxNotionalFraction = maxNotionalFraction;
        this.maxPriceDeviation = maxPriceDeviation;
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public RiskRule.Level level() {
        return RiskRule.Level.ORDER;
    }

    @Override
    public Optional<RiskRejection> check(OrderFacts facts) {
        // Fat-finger guard first, so an order that is both mispriced and oversized reports the CRITICAL
        // price objection rather than masking it behind the WARNING size objection.
        BigDecimal limitPrice = facts.limitPrice();
        if (limitPrice != null) {
            BigDecimal mark = facts.signal().price();
            BigDecimal deviation = limitPrice.subtract(mark).abs();
            // maxPriceDeviation x mark, the absolute distance a LIMIT price may sit from the mark.
            BigDecimal allowedDeviation = Money.of(mark.multiply(maxPriceDeviation, Money.MC));
            if (deviation.compareTo(allowedDeviation) > 0) {
                return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.ORDER,
                        RiskAlertEvent.Severity.CRITICAL,
                        "limit price " + limitPrice.toPlainString() + " is " + deviation.toPlainString()
                                + " from mark " + mark.toPlainString() + ", beyond the "
                                + allowedDeviation.toPlainString() + " allowed by maxPriceDeviation "
                                + maxPriceDeviation.toPlainString() + ": fat-finger guard, order refused"));
            }
        }

        // Single-order notional cap, exempting reductions: an order that shrinks the position is
        // de-risking and must not be trapped by a cap meant to bound new exposure (OrderFacts.reduces).
        if (!facts.reduces()) {
            BigDecimal equity = facts.signal().equity();
            BigDecimal cap = Money.of(equity.multiply(maxNotionalFraction, Money.MC));
            if (facts.orderNotional().compareTo(cap) > 0) {
                return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.ORDER,
                        RiskAlertEvent.Severity.WARNING,
                        "order notional " + facts.orderNotional().toPlainString() + " exceeds "
                                + maxNotionalFraction.toPlainString() + " x equity " + equity.toPlainString()
                                + " = " + cap.toPlainString() + ": single-order cap, order refused"));
            }
        }
        return Optional.empty();
    }
}
