package com.ciaozn.alphatrader.common.portfolio;

import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.math.BigDecimal;

/**
 * Net position in one symbol (spec §5). Quantities are absolute; the sign lives in
 * {@link #direction()}. Immutable - {@link Portfolio} replaces the whole record on change.
 */
public record Position(
        Symbol symbol,
        Direction direction,
        BigDecimal qty,
        BigDecimal entryPrice) {

    public static Position flat(Symbol symbol) {
        return new Position(symbol, Direction.FLAT, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public boolean isEmpty() {
        return qty.signum() == 0;
    }

    /** +qty for LONG, -qty for SHORT, 0 for FLAT. */
    public BigDecimal signedQty() {
        return switch (direction) {
            case LONG -> qty;
            case SHORT -> qty.negate();
            case FLAT -> BigDecimal.ZERO;
        };
    }

    public BigDecimal notional(BigDecimal markPrice) {
        return qty.multiply(markPrice);
    }

    /** Mark-to-market profit; negative for an underwater short. Zero when flat. */
    public BigDecimal unrealizedPnl(BigDecimal markPrice) {
        if (isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = markPrice.subtract(entryPrice).multiply(qty);
        return direction == Direction.SHORT ? diff.negate() : diff;
    }

    static Position ofSigned(Symbol symbol, BigDecimal signedQty, BigDecimal entryPrice) {
        int sign = signedQty.signum();
        if (sign == 0) {
            return flat(symbol);
        }
        return new Position(symbol,
                sign > 0 ? Direction.LONG : Direction.SHORT,
                signedQty.abs(),
                entryPrice);
    }
}
