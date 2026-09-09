package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 账户级 (FR-RK-02, DESIGN §8): the account's own health gate. It runs in the signal stage - before
 * FR-RK-07 sizes anything - so an over-stressed book refuses a new opening for the <em>account's</em>
 * reason instead of the signal disappearing into a sizing {@code NoTrade} that hides why.
 *
 * <p>Two thresholds, both read off the <b>current</b> book (there is no quantity yet at this stage):
 * <ul>
 *   <li><b>hard leverage ceiling</b>: {@code totalNotional / equity <= maxLeverage} (3x);</li>
 *   <li><b>margin floor</b>: {@code equity / usedMargin >= minMarginRatio} (150%), where
 *       {@code usedMargin = totalNotional / maxLeverage}.</li>
 * </ul>
 *
 * <p><b>The margin ratio is this system's own definition, not Binance's (取舍 12).</b> Here it is
 * {@code equity / usedMargin} - higher is healthier. Binance's field of the same name is maintenance
 * margin over position notional and runs the <em>other</em> way (higher is closer to liquidation), so
 * wiring the exchange field straight in would pass exactly the orders that are about to be liquidated.
 * The rule self-computes from the book; the exchange's number belongs to reconciliation (T318), not to
 * this gate.
 *
 * <p>Because {@code usedMargin} divides by {@code maxLeverage}, the floor is {@code maxLeverage /
 * leverage}, so with the shipped numbers it binds tighter than the ceiling: openings stop once
 * leverage passes {@code 3 / 1.5 = 2x} (margin, WARNING - the early warning), and the 3x ceiling
 * (leverage, CRITICAL - the hard stop) is the backstop for a book that got there by price move rather
 * than by opening. Checking the ceiling first means a book over 3x reports the hard stop, not the
 * softer one.
 *
 * <p><b>Only openings are gated.</b> A {@link Direction#FLAT} signal always passes: an unhealthy
 * account must be able to de-risk, and blocking the exit would lock it into the very exposure that
 * tripped the rule. {@code LONG} and {@code SHORT} both mean "take on directional exposure" and are
 * refused while unhealthy. Pre-size there is no quantity, so a flip that would net the book
 * <em>down</em> cannot be told from one that grows it; for a risk gate the conservative choice is to
 * refuse and let the strategy FLAT first.
 *
 * <p>A non-positive equity passes through an explicit branch on purpose: it is the sizer's rejection
 * ({@code PositionSizer.RULE_NO_EQUITY}), and claiming it here too would give one condition two owners
 * and two rule ids in the interception record for the same fact. The branch is load-bearing because a
 * zero equity would otherwise trip the ceiling ({@code totalNotional > maxLeverage x 0 = 0}) and report
 * a bogus CRITICAL. A flat book ({@code totalNotional == 0}) needs no such branch - zero notional is
 * never over the ceiling and never under the margin floor ({@code equity x maxLeverage < minMarginRatio
 * x 0} is false for positive equity), so it passes on the threshold math itself; an explicit guard there
 * would be redundant, which mutation testing confirms (removing it changes no verdict).
 *
 * <p>The decision uses cross-multiplication rather than division, so it is exact (no rounding to
 * disagree with the threshold at the boundary) and the rejection can quote the very inequality that
 * fired. Stateless and Spring-free: the same call in backtest and live gives the same verdict
 * (FR-BT-06).
 */
public final class AccountRule implements SignalRule {

    /** The account level's one id; which of the two thresholds fired is carried in the detail. */
    public static final String RULE_ID = "RK-02-account";

    private final BigDecimal maxLeverage;
    private final BigDecimal minMarginRatio;

    /**
     * @param maxLeverage   hard ceiling on {@code totalNotional / equity}, e.g. 3 for 3x
     * @param minMarginRatio floor on {@code equity / usedMargin}, e.g. 1.50 for 150%
     */
    public AccountRule(BigDecimal maxLeverage, BigDecimal minMarginRatio) {
        if (maxLeverage == null || maxLeverage.signum() <= 0) {
            throw new IllegalArgumentException(
                    "maxLeverage must be positive, got " + maxLeverage
                            + ": a ceiling at or below zero would refuse every opening, which is trading"
                            + " switched off reporting itself as a stream of risk alerts");
        }
        if (minMarginRatio == null || minMarginRatio.signum() <= 0) {
            throw new IllegalArgumentException(
                    "minMarginRatio must be positive, got " + minMarginRatio
                            + ": a floor at or below zero can never be breached, so the margin check"
                            + " would look configured and never fire");
        }
        this.maxLeverage = maxLeverage;
        this.minMarginRatio = minMarginRatio;
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public RiskRule.Level level() {
        return RiskRule.Level.ACCOUNT;
    }

    @Override
    public Optional<RiskRejection> check(SignalFacts facts) {
        // De-risking is always allowed: blocking the exit would trap the account in the exposure
        // that tripped the rule.
        if (facts.signal().direction() == Direction.FLAT) {
            return Optional.empty();
        }
        BigDecimal equity = facts.equity();
        // No-equity is the sizer's rejection, not this rule's - one owner per condition.
        if (equity == null || equity.signum() <= 0) {
            return Optional.empty();
        }
        // No flat-book guard: totalNotional 0 is never over the ceiling and never under the floor, so
        // a flat book passes on the math below (and Portfolio.totalNotional() is never null or negative).
        BigDecimal totalNotional = facts.totalNotional();

        // Hard ceiling: totalNotional / equity > maxLeverage  <=>  totalNotional > maxLeverage x equity.
        BigDecimal leverageCeiling = maxLeverage.multiply(equity);
        if (totalNotional.compareTo(leverageCeiling) > 0) {
            return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.ACCOUNT,
                    RiskAlertEvent.Severity.CRITICAL,
                    "total notional " + totalNotional.toPlainString() + " exceeds "
                            + maxLeverage.toPlainString() + "x equity " + equity.toPlainString()
                            + " = " + leverageCeiling.toPlainString()
                            + ": account is over the hard leverage ceiling, new openings refused"));
        }

        // Margin floor: equity / (totalNotional / maxLeverage) < minMarginRatio
        //   <=>  equity x maxLeverage < minMarginRatio x totalNotional.
        BigDecimal equityTimesLeverage = equity.multiply(maxLeverage);
        BigDecimal floorTimesNotional = minMarginRatio.multiply(totalNotional);
        if (equityTimesLeverage.compareTo(floorTimesNotional) < 0) {
            return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.ACCOUNT,
                    RiskAlertEvent.Severity.WARNING,
                    "equity " + equity.toPlainString() + " x " + maxLeverage.toPlainString()
                            + " (maxLeverage) = " + equityTimesLeverage.toPlainString() + " is below "
                            + minMarginRatio.toPlainString() + " (minMarginRatio) x total notional "
                            + totalNotional.toPlainString() + " = " + floorTimesNotional.toPlainString()
                            + ": margin ratio is under the floor, new openings refused"));
        }
        return Optional.empty();
    }
}
