package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.math.BigDecimal;

/**
 * One round trip on one symbol: from flat, through any number of adds and partial closes, back
 * to flat. A fill that flips the position ends one trade and starts another, so a trade never
 * changes direction - which is what makes win rate and profit factor mean anything.
 *
 * <p>A trade still open when the run ends is reported too ({@code open == true}), valued at the
 * last mark. It appears in the per-trade detail but is excluded from win rate and profit factor:
 * counting an unrealized result as a win or a loss would make those numbers depend on where the
 * data happened to stop.
 *
 * <p>{@code closedTs} is the exit time for a closed trade and the time of the most recent fill
 * for an open one, so the report can sort both by a single field.
 */
public record Trade(
        Symbol symbol,
        Direction direction,
        long openedTs,
        long closedTs,
        BigDecimal openedQty,
        BigDecimal closedQty,
        BigDecimal avgEntryPrice,
        BigDecimal avgExitPrice,
        BigDecimal grossPnl,
        BigDecimal fees,
        BigDecimal netPnl,
        boolean open) {

    public enum Direction {
        LONG, SHORT
    }

    public Trade {
        grossPnl = Money.of(grossPnl);
        fees = Money.of(fees);
        netPnl = Money.of(netPnl);
    }

    /** Average entry price, or zero if nothing was ever opened (cannot happen for a real trade). */
    public static BigDecimal average(BigDecimal notional, BigDecimal qty) {
        return qty.signum() == 0 ? Money.zero() : Money.of(Money.divide(notional, qty));
    }

    public BigDecimal openQty() {
        return openedQty.subtract(closedQty);
    }

    public boolean isWin() {
        return netPnl.signum() > 0;
    }

    public boolean isLoss() {
        return netPnl.signum() < 0;
    }

    /** How long the position was held; for an open trade, how long it has been held so far. */
    public long holdingMillis() {
        return closedTs - openedTs;
    }
}
