package com.ciaozn.alphatrader.common.portfolio;

import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-account net-position book: cash, positions, mark prices, realized P&L, fees and
 * funding (spec §5 Position/Account, FR-EX-06).
 *
 * <p>This is shared domain state, deliberately placed in alpha-common: the backtest
 * simulated matcher and the live OMS must update positions with the SAME arithmetic,
 * otherwise backtest and live results drift apart (FR-BT-06).
 *
 * <p>Not thread-safe by design - it is only ever touched on the single event-engine thread.
 * All maps are insertion-ordered so iteration (reports, snapshots) is deterministic (NFR-04).
 * All money math uses BigDecimal with a fixed scale and rounding mode; doubles never appear.
 */
public final class Portfolio {

    private final BigDecimal startingEquity;
    private final Map<Symbol, BigDecimal> signedQty = new LinkedHashMap<>();
    private final Map<Symbol, BigDecimal> entryPrice = new LinkedHashMap<>();
    private final Map<Symbol, BigDecimal> markPrice = new LinkedHashMap<>();

    private BigDecimal cash;
    private BigDecimal realizedPnl = zero();
    private BigDecimal feeTotal = zero();
    private BigDecimal fundingTotal = zero();

    public Portfolio(BigDecimal startingEquity) {
        this.startingEquity = money(startingEquity);
        this.cash = this.startingEquity;
    }

    /** Result of one fill: what it realized and the position left behind. */
    public record FillResult(BigDecimal realizedPnl, BigDecimal fee, Position position) {
    }

    // ------------------------------------------------------------------ market state

    /** Latest observed price for a symbol (k-line close or ticker). Drives equity and funding. */
    public void mark(Symbol symbol, BigDecimal price) {
        markPrice.put(symbol, price);
    }

    /** Mark price, falling back to entry price so equity stays defined before the first mark. */
    public BigDecimal markOf(Symbol symbol) {
        BigDecimal mark = markPrice.get(symbol);
        if (mark != null) {
            return mark;
        }
        return entryPrice.getOrDefault(symbol, zero());
    }

    // ------------------------------------------------------------------ mutations

    /**
     * Applies one execution to the net position using average-cost accounting:
     * adding re-weights the entry price, reducing realizes P&L on the closed part,
     * flipping realizes the whole old position and re-opens at the fill price.
     * Fees always reduce cash.
     */
    public FillResult applyFill(Symbol symbol, Side side, BigDecimal price, BigDecimal qty, BigDecimal fee) {
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("Fill qty must be > 0, got " + qty);
        }
        BigDecimal delta = side == Side.BUY ? qty : qty.negate();
        BigDecimal old = signedQty.getOrDefault(symbol, zero());
        BigDecimal updated = old.add(delta);
        BigDecimal oldEntry = entryPrice.getOrDefault(symbol, zero());

        BigDecimal realized;
        if (old.signum() == 0) {
            realized = zero();
            entryPrice.put(symbol, price);
        } else if (updated.signum() != 0 && old.signum() != updated.signum()) {
            // Flip: close the entire old position at this price, then open the remainder.
            realized = closePnl(old, oldEntry, price);
            entryPrice.put(symbol, price);
        } else if (updated.abs().compareTo(old.abs()) <= 0) {
            // Reduce (or exact close): realize on the closed part, entry price unchanged.
            BigDecimal closed = old.abs().subtract(updated.abs());
            realized = closePnl(old.signum() > 0 ? closed : closed.negate(), oldEntry, price);
        } else {
            // Add: weighted average entry.
            realized = zero();
            BigDecimal weighted = oldEntry.multiply(old.abs()).add(price.multiply(delta.abs()));
            entryPrice.put(symbol, Money.divide(weighted, updated.abs()));
        }

        signedQty.put(symbol, updated);
        realized = money(realized);
        BigDecimal chargedFee = money(fee);
        cash = cash.add(realized).subtract(chargedFee);
        realizedPnl = realizedPnl.add(realized);
        feeTotal = feeTotal.add(chargedFee);

        return new FillResult(realized, chargedFee, position(symbol));
    }

    /**
     * Reconciliation write (FR-EX-04): the exchange is the source of truth, so when its position
     * disagrees with this book, the book is set to the exchange's numbers.
     *
     * <p><b>Not a fill.</b> The only other way to move a position is {@link #applyFill}, and using it
     * here would be a lie: it would invent a fill price, realize P&L against an entry price we no
     * longer trust, and charge a fee that was never charged - three numbers that then disagree with
     * the exchange's own statement of the account, which is exactly the disagreement being fixed.
     * A correction sets quantity and entry price and leaves realized P&L alone, because what
     * happened between the last fill and this correction is unknown, not zero.
     *
     * @param signedQty  signed: positive long, negative short, zero to drop the symbol entirely
     * @param entryPrice the exchange's entry price for the remaining position
     */
    public void setPosition(Symbol symbol, BigDecimal signedQty, BigDecimal entryPrice) {
        BigDecimal qty = money(signedQty);
        if (qty.signum() == 0) {
            this.signedQty.remove(symbol);
            this.entryPrice.remove(symbol);
            return;
        }
        this.signedQty.put(symbol, qty);
        this.entryPrice.put(symbol, money(entryPrice));
    }

    /**
     * Perpetual funding settlement (spec edge case 5): notional * rate, paid by longs when
     * the rate is positive and received by shorts. Returns the cash amount charged
     * (positive = cost, negative = income).
     */
    public BigDecimal applyFunding(Symbol symbol, BigDecimal fundingRate) {
        BigDecimal qty = signedQty.getOrDefault(symbol, zero());
        if (qty.signum() == 0 || fundingRate.signum() == 0) {
            return zero();
        }
        BigDecimal charge = money(qty.multiply(markOf(symbol)).multiply(fundingRate));
        cash = cash.subtract(charge);
        fundingTotal = fundingTotal.add(charge);
        return charge;
    }

    private static BigDecimal closePnl(BigDecimal closedSignedQty, BigDecimal entry, BigDecimal exit) {
        return exit.subtract(entry).multiply(closedSignedQty);
    }

    // ------------------------------------------------------------------ views

    public Position position(Symbol symbol) {
        return Position.ofSigned(symbol, signedQty.getOrDefault(symbol, zero()),
                entryPrice.getOrDefault(symbol, zero()));
    }

    /** Open (non-zero) positions in first-touched order. */
    public List<Position> openPositions() {
        List<Position> open = new ArrayList<>();
        for (Symbol symbol : signedQty.keySet()) {
            Position position = position(symbol);
            if (!position.isEmpty()) {
                open.add(position);
            }
        }
        return Collections.unmodifiableList(open);
    }

    public BigDecimal unrealizedPnl() {
        BigDecimal total = zero();
        for (Symbol symbol : signedQty.keySet()) {
            total = total.add(position(symbol).unrealizedPnl(markOf(symbol)));
        }
        return money(total);
    }

    /** Cash plus mark-to-market P&L - the number every risk rule and the report use. */
    public BigDecimal equity() {
        return money(cash.add(unrealizedPnl()));
    }

    public BigDecimal totalNotional() {
        BigDecimal total = zero();
        for (Symbol symbol : signedQty.keySet()) {
            total = total.add(position(symbol).notional(markOf(symbol)).abs());
        }
        return money(total);
    }

    public Map<Symbol, Position> positionsBySymbol() {
        Map<Symbol, Position> snapshot = new LinkedHashMap<>();
        signedQty.keySet().forEach(symbol -> snapshot.put(symbol, position(symbol)));
        return Collections.unmodifiableMap(snapshot);
    }

    public BigDecimal cash() {
        return cash;
    }

    public BigDecimal startingEquity() {
        return startingEquity;
    }

    public BigDecimal realizedPnl() {
        return realizedPnl;
    }

    public BigDecimal feeTotal() {
        return feeTotal;
    }

    public BigDecimal fundingTotal() {
        return fundingTotal;
    }

    private static BigDecimal zero() {
        return Money.zero();
    }

    private static BigDecimal money(BigDecimal value) {
        return Money.of(value);
    }
}
