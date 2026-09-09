package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups fills into round trips (FR-BT-04) - the unit win rate and profit factor are defined
 * on. A fill is not a trade: one position is usually built and unwound over several, and
 * counting each separately would call every scalped leg a "trade" and make a 40% win rate look
 * like a 100% one.
 *
 * <p>Because {@link Portfolio} keeps a single net position per symbol, at most one trade per
 * symbol can be open, so grouping is unambiguous: the trade closes when the signed quantity
 * returns to zero. A fill that flips the sign is split into a closing leg and an opening leg,
 * with the fee prorated by quantity so the two halves sum exactly to what was charged - a trade
 * that changed direction would otherwise mix two different theses into one number.
 *
 * <p>Trade P&L is computed from cash flows (what was paid to open, what was received to close,
 * plus the still-open remainder at the mark) rather than by reading the book's realized figure.
 * For a round trip that returns to flat the two are equal by construction, and this way the
 * average-cost arithmetic stays in exactly one place - {@link Portfolio}. The difference is
 * bounded by the sub-satoshi rounding of that one division, which
 * {@code TradeTrackerTest} pins against the book.
 *
 * <p>Runs on the event-engine thread only, so no synchronization; maps are insertion-ordered so
 * the report is byte-identical across runs (NFR-04).
 */
public final class TradeTracker implements EventHandler {

    private final Portfolio portfolio;
    private final Map<Symbol, OpenTrade> open = new LinkedHashMap<>();
    private final List<Trade> closed = new ArrayList<>();
    private long fills;

    public TradeTracker(Portfolio portfolio) {
        this.portfolio = portfolio;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        if (event instanceof FillEvent fill) {
            onFill(fill);
        }
    }

    private void onFill(FillEvent fill) {
        BigDecimal qty = fill.qty();
        if (qty.signum() <= 0) {
            throw new IllegalArgumentException("Fill qty must be > 0, got " + qty);
        }
        fills++;
        Symbol symbol = fill.symbol();
        OpenTrade current = open.get(symbol);
        BigDecimal held = current == null ? Money.zero() : current.signedQty;
        BigDecimal updated = held.add(fill.side() == Side.BUY ? qty : qty.negate());

        if (current != null && held.signum() != 0 && updated.signum() != 0
                && held.signum() != updated.signum()) {
            splitFlip(current, fill, held.abs(), qty);
            return;
        }
        if (current == null) {
            current = start(symbol, fill.side(), fill.timestamp());
            open.put(symbol, current);
        }
        applyLeg(current, fill.side(), fill.price(), qty, fill.fee(), fill.timestamp());
        if (current.signedQty.signum() == 0) {
            open.remove(symbol);
            closed.add(current.toTrade(portfolio.markOf(symbol)));
        }
    }

    /**
     * One fill that reverses the position: the part up to the held quantity closes the old
     * trade, the remainder opens a new one in the other direction.
     */
    private void splitFlip(OpenTrade current, FillEvent fill, BigDecimal closingQty, BigDecimal totalQty) {
        BigDecimal closingFee = prorate(fill.fee(), closingQty, totalQty);
        // Derived, not prorated again: the two halves must add up to exactly what was charged.
        BigDecimal openingFee = Money.of(fill.fee()).subtract(closingFee);

        applyLeg(current, fill.side(), fill.price(), closingQty, closingFee, fill.timestamp());
        closed.add(current.toTrade(portfolio.markOf(fill.symbol())));
        open.remove(fill.symbol());

        OpenTrade next = start(fill.symbol(), fill.side(), fill.timestamp());
        applyLeg(next, fill.side(), fill.price(), totalQty.subtract(closingQty), openingFee, fill.timestamp());
        open.put(fill.symbol(), next);
    }

    private static OpenTrade start(Symbol symbol, Side side, long ts) {
        return new OpenTrade(symbol, side == Side.BUY ? Trade.Direction.LONG : Trade.Direction.SHORT, ts);
    }

    private static void applyLeg(OpenTrade trade, Side side, BigDecimal price, BigDecimal qty,
                                 BigDecimal fee, long ts) {
        BigDecimal notional = Money.of(price.multiply(qty, Money.MC));
        boolean opening = (side == Side.BUY) == (trade.direction == Trade.Direction.LONG);
        if (opening) {
            trade.entryNotional = trade.entryNotional.add(notional);
            trade.openedQty = trade.openedQty.add(qty);
        } else {
            trade.exitNotional = trade.exitNotional.add(notional);
            trade.closedQty = trade.closedQty.add(qty);
        }
        trade.signedQty = trade.signedQty.add(side == Side.BUY ? qty : qty.negate());
        trade.fees = trade.fees.add(Money.of(fee));
        trade.lastTs = ts;
    }

    private static BigDecimal prorate(BigDecimal fee, BigDecimal part, BigDecimal whole) {
        if (part.compareTo(whole) == 0) {
            return Money.of(fee);
        }
        return Money.of(Money.divide(fee.multiply(part, Money.MC), whole));
    }

    /** Closed round trips, in the order they finished. */
    public List<Trade> closedTrades() {
        return List.copyOf(closed);
    }

    /** Positions still open at the end of the run, valued at the last mark. */
    public List<Trade> openTrades() {
        List<Trade> snapshot = new ArrayList<>(open.size());
        open.values().forEach(trade -> snapshot.add(trade.toTrade(portfolio.markOf(trade.symbol))));
        return List.copyOf(snapshot);
    }

    /** Every trade, closed and open, oldest first - the report's per-trade table. */
    public List<Trade> trades() {
        List<Trade> all = new ArrayList<>(closed);
        all.addAll(openTrades());
        // Stable: equal open times keep insertion order, which is already deterministic.
        all.sort(Comparator.comparingLong(Trade::openedTs));
        return List.copyOf(all);
    }

    public long fillsTracked() {
        return fills;
    }

    /** Running state of the one position a symbol can have at a time. */
    private static final class OpenTrade {

        private final Symbol symbol;
        private final Trade.Direction direction;
        private final long openedTs;
        private BigDecimal signedQty = Money.zero();
        private BigDecimal openedQty = Money.zero();
        private BigDecimal closedQty = Money.zero();
        private BigDecimal entryNotional = Money.zero();
        private BigDecimal exitNotional = Money.zero();
        private BigDecimal fees = Money.zero();
        private long lastTs;

        private OpenTrade(Symbol symbol, Trade.Direction direction, long openedTs) {
            this.symbol = symbol;
            this.direction = direction;
            this.openedTs = openedTs;
            this.lastTs = openedTs;
        }

        private BigDecimal openQty() {
            return openedQty.subtract(closedQty);
        }

        private Trade toTrade(BigDecimal mark) {
            BigDecimal openQty = openQty();
            // Structurally zero for a closed trade, so a stale mark can never leak into its P&L:
            // fills are published before the executor marks the new bar's close.
            BigDecimal openNotional = openQty.signum() == 0
                    ? Money.zero()
                    : Money.of(openQty.multiply(mark, Money.MC));
            BigDecimal gross = direction == Trade.Direction.LONG
                    ? exitNotional.add(openNotional).subtract(entryNotional)
                    : entryNotional.subtract(exitNotional).subtract(openNotional);
            BigDecimal net = gross.subtract(fees);
            return new Trade(symbol, direction, openedTs, lastTs, openedQty, closedQty,
                    Trade.average(entryNotional, openedQty), Trade.average(exitNotional, closedQty),
                    gross, fees, net, openQty.signum() != 0);
        }
    }
}
