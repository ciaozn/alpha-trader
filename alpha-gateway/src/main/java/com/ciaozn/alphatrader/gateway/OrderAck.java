package com.ciaozn.alphatrader.gateway;

/** Exchange acknowledgment of an order placement. */
public record OrderAck(
        String clientOrderId,
        String exchangeOrderId,
        boolean accepted,
        String rejectReason) {

    public static OrderAck accepted(String clientOrderId, String exchangeOrderId) {
        return new OrderAck(clientOrderId, exchangeOrderId, true, null);
    }

    public static OrderAck rejected(String clientOrderId, String reason) {
        return new OrderAck(clientOrderId, null, false, reason);
    }
}
