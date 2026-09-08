package com.ciaozn.alphatrader.common.event;

import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;

/** Order lifecycle notification emitted by the OMS on every state transition (FR-EX-01). */
@JsonTypeName("orderUpdate")
public record OrderUpdateEvent(
        long eventId,
        long timestamp,
        String clientOrderId,
        OrderStatus status,
        BigDecimal filledQty,
        BigDecimal avgPrice) implements Event {

    public static OrderUpdateEvent of(String clientOrderId, OrderStatus status,
                                      BigDecimal filledQty, BigDecimal avgPrice, long businessTs) {
        return new OrderUpdateEvent(EventIds.next(), businessTs, clientOrderId, status, filledQty, avgPrice);
    }
}
