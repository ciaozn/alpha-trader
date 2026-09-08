package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.math.BigDecimal;

/** Actual exchange-side position (FR-EX-04 reconciliation target). */
public record Position(
        Symbol symbol,
        Direction direction,
        BigDecimal qty,
        BigDecimal entryPrice,
        BigDecimal unrealizedPnl) {
}
