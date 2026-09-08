package com.ciaozn.alphatrader.common.event;

import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;

/**
 * An order that survived the risk pipeline, addressed to the OMS.
 * clientOrderId is the global idempotency key (FR-EX-02).
 */
@JsonTypeName("orderRequest")
public record OrderRequestEvent(
        long eventId,
        long timestamp,
        String clientOrderId,
        Symbol symbol,
        Side side,
        OrderType orderType,
        BigDecimal qty,
        BigDecimal price) implements Event {

    public static OrderRequestEvent of(String clientOrderId, Symbol symbol, Side side,
                                       OrderType type, BigDecimal qty, BigDecimal price, long businessTs) {
        return new OrderRequestEvent(EventIds.next(), businessTs, clientOrderId, symbol, side, type, qty, price);
    }
}
