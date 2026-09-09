package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;

import java.math.BigDecimal;

/**
 * The candidate order plus the account <em>as it would be if that order filled</em>. The projected
 * numbers are what makes the portfolio-level rules (FR-RK-04) meaningful: checking the current
 * total notional would pass every order that grows an already-oversized book one step at a time,
 * and checking only the order's own notional would miss an order that is small relative to equity
 * but doubles one symbol.
 *
 * <p><b>The notional is estimated at the mark, and that is the only honest option here.</b> The gate
 * sends MARKET orders only (a limit price at this point would be either look-ahead bias in backtest
 * or a stale quote in live), so an order-level cap that insisted on a real price would never fire on
 * anything the system actually sends - it would be a rule that looks configured and never runs.
 * The estimate is conservative in the sense that matters: it is the same price the sizer used to
 * produce the quantity.
 *
 * <p>{@code limitPrice} is the candidate order's <em>own</em> price, or {@code null} for a MARKET
 * order, which has no price - it executes at the touch. It exists solely for FR-RK-03's fat-finger
 * check ({@code OrderLimitsRule}): a MARKET order has nothing to deviate from the mark, so that check
 * skips it, while a LIMIT order's price is compared against the mark and refused if it is too far off.
 * The notional above deliberately stays estimated at the mark and does <em>not</em> switch to
 * {@code limitPrice} when one is present - the cap has to fire on the orders the gate actually sends
 * (MARKET, no price), and a LIMIT within the deviation band would move the notional by at most that
 * band anyway.
 */
public record OrderFacts(
        SignalFacts signal,
        Side side,
        BigDecimal qty,
        BigDecimal limitPrice,
        BigDecimal orderNotional,
        BigDecimal projectedSignedQty,
        BigDecimal projectedSymbolNotional,
        BigDecimal projectedTotalNotional) {

    /** A MARKET candidate order: no price of its own, so the fat-finger check has nothing to compare. */
    public static OrderFacts of(SignalFacts facts, Side side, BigDecimal qty) {
        return of(facts, side, qty, null);
    }

    /**
     * @param limitPrice the candidate order's own price, or {@code null} for MARKET
     */
    public static OrderFacts of(SignalFacts facts, Side side, BigDecimal qty, BigDecimal limitPrice) {
        BigDecimal orderNotional = Money.of(qty.multiply(facts.price()));
        BigDecimal projectedSignedQty = side == Side.BUY
                ? facts.signedQty().add(qty)
                : facts.signedQty().subtract(qty);
        BigDecimal projectedSymbolNotional = Money.of(projectedSignedQty.abs().multiply(facts.price()));
        // Swap this symbol's slice of the total rather than re-marking the whole book: the other
        // positions have not moved, and re-reading them would make the projection depend on marks
        // arriving between the two reads.
        BigDecimal projectedTotalNotional = Money.of(facts.totalNotional()
                .subtract(facts.symbolNotional())
                .add(projectedSymbolNotional));
        return new OrderFacts(facts, side, qty, limitPrice, orderNotional, projectedSignedQty,
                projectedSymbolNotional, projectedTotalNotional);
    }

    /**
     * True when this order strictly shrinks the position magnitude in its own symbol - a partial or
     * full close, but not a flip to an equal-or-larger size. Such an order is de-risking: it can only
     * move this symbol's notional, and therefore the book's total notional, <em>down</em>, so it can
     * never newly breach a cap - but on a book that is already over one, its projected notional is
     * still over, and capping it would trap a position the account is trying to exit. Both order-stage
     * caps (FR-RK-03 single-order, FR-RK-04 portfolio) therefore exempt a reduction, and the predicate
     * lives here so the two rules cannot drift apart about what counts as de-risking.
     */
    public boolean reduces() {
        return projectedSignedQty.abs().compareTo(signal.signedQty().abs()) < 0;
    }
}
