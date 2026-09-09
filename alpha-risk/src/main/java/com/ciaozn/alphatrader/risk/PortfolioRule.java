package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Money;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 组合级 (FR-RK-04, DESIGN §8): caps the book <em>as it would be if this order filled</em>, using the
 * projected notionals {@link OrderFacts} already computed - not the current book and not the order in
 * isolation. Both narrower views are wrong: checking the current total would pass every order that grows
 * an already-oversized book one step at a time, and checking only the order's own notional would miss an
 * order that is small relative to equity but doubles one symbol. Two caps under one id:
 * <ul>
 *   <li><b>total notional cap</b>: the projected book must stay within {@code maxTotalFraction} (60%) of
 *       equity - the account-wide directional exposure limit;</li>
 *   <li><b>single-symbol cap</b>: the projected position in this symbol must stay within
 *       {@code maxSymbolFraction} (30%) of equity - the concentration limit.</li>
 * </ul>
 * The total is checked first so an order that breaches both reports the broader account-level limit.
 *
 * <p>Both caps are WARNING rather than CRITICAL: an order declined here is the gate doing its job and the
 * account is in no danger - genuine danger (a book over the leverage ceiling, usually by appreciation
 * rather than by opening) is {@link AccountRule}'s CRITICAL, which sits at a far higher threshold (3x
 * equity) precisely because this rule keeps order-driven exposure under 60%. The two are complementary,
 * not redundant: this caps what new orders may add, that one catches a book the market has already grown.
 *
 * <p><b>Reductions are exempt</b> ({@link OrderFacts#reduces()}), for the same reason as everywhere else
 * in the gate: a reduction can only move notional toward compliance, but on a book that is <em>already</em>
 * over a cap its projection is still over, so capping it would trap a position the account is trying to
 * exit. An over-concentrated book must always be able to trade down.
 *
 * <p>Equity is guaranteed usable (the sizer rejected {@code RULE_NO_EQUITY} before the order stage), and
 * the mark is already folded into the projected notionals by {@code OrderFacts.of}, so this rule does no
 * price math of its own. Decisions use cross-multiplication, exact at the boundary: an order that lands
 * exactly on a cap passes. Stateless and Spring-free, so backtest and live give the same verdict
 * (FR-BT-06).
 */
public final class PortfolioRule implements OrderRule {

    /** The portfolio level's one id; which of the two caps fired is carried in the detail. */
    public static final String RULE_ID = "RK-04-portfolio";

    private final BigDecimal maxTotalFraction;
    private final BigDecimal maxSymbolFraction;

    /**
     * @param maxTotalFraction  cap on the projected book's total notional as a fraction of equity, e.g. 0.60
     * @param maxSymbolFraction cap on the projected single-symbol notional, e.g. 0.30; must not exceed the
     *                          total cap, since one symbol's notional is part of the book's total
     */
    public PortfolioRule(BigDecimal maxTotalFraction, BigDecimal maxSymbolFraction) {
        if (maxTotalFraction == null || maxTotalFraction.signum() <= 0) {
            throw new IllegalArgumentException(
                    "maxTotalFraction must be positive, got " + maxTotalFraction
                            + ": a total cap at or below zero would refuse every order that is not a"
                            + " reduction, which is trading switched off reporting itself as risk alerts");
        }
        if (maxSymbolFraction == null || maxSymbolFraction.signum() <= 0) {
            throw new IllegalArgumentException(
                    "maxSymbolFraction must be positive, got " + maxSymbolFraction
                            + ": a symbol cap at or below zero would refuse every order that is not a"
                            + " reduction in that symbol");
        }
        if (maxSymbolFraction.compareTo(maxTotalFraction) > 0) {
            throw new IllegalArgumentException(
                    "maxSymbolFraction " + maxSymbolFraction + " must not exceed maxTotalFraction "
                            + maxTotalFraction + ": one symbol's notional is part of the book's total, so a"
                            + " symbol cap above the total cap could never bind before it and contradicts it");
        }
        this.maxTotalFraction = maxTotalFraction;
        this.maxSymbolFraction = maxSymbolFraction;
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public RiskRule.Level level() {
        return RiskRule.Level.PORTFOLIO;
    }

    @Override
    public Optional<RiskRejection> check(OrderFacts facts) {
        // De-risking is always allowed: an over-cap book must be able to trade down, and a reduction's
        // projection is still over a cap the book already breached, so capping it would trap the position.
        if (facts.reduces()) {
            return Optional.empty();
        }
        BigDecimal equity = facts.signal().equity();

        // Total cap first, so an order breaching both reports the broader account-wide limit.
        BigDecimal totalCap = Money.of(equity.multiply(maxTotalFraction, Money.MC));
        if (facts.projectedTotalNotional().compareTo(totalCap) > 0) {
            return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.PORTFOLIO,
                    RiskAlertEvent.Severity.WARNING,
                    "projected total notional " + facts.projectedTotalNotional().toPlainString()
                            + " exceeds " + maxTotalFraction.toPlainString() + " x equity "
                            + equity.toPlainString() + " = " + totalCap.toPlainString()
                            + ": portfolio total cap, order refused"));
        }

        BigDecimal symbolCap = Money.of(equity.multiply(maxSymbolFraction, Money.MC));
        if (facts.projectedSymbolNotional().compareTo(symbolCap) > 0) {
            return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.PORTFOLIO,
                    RiskAlertEvent.Severity.WARNING,
                    "projected " + facts.signal().symbol().unified() + " notional "
                            + facts.projectedSymbolNotional().toPlainString() + " exceeds "
                            + maxSymbolFraction.toPlainString() + " x equity " + equity.toPlainString()
                            + " = " + symbolCap.toPlainString() + ": single-symbol cap, order refused"));
        }
        return Optional.empty();
    }
}
