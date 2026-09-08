package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.math.BigDecimal;

/** A resting order as the exchange sees it (reconciliation input, FR-EX-04). */
public record OpenOrder(
        String clientOrderId,
        Symbol symbol,
        Side side,
        BigDecimal qty,
        BigDecimal price,
        OrderStatus status) {
}
