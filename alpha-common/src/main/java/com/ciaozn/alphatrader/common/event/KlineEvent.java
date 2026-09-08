package com.ciaozn.alphatrader.common.event;

import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Standardized k-line pushed by a gateway (FR-GW-01).
 * {@code closed=true} only when the bar is final - strategies must act on closed bars
 * to avoid look-ahead bias (FR-BT-02 discipline).
 */
@JsonTypeName("kline")
public record KlineEvent(
        long eventId,
        long timestamp,
        Symbol symbol,
        Interval interval,
        Kline kline,
        boolean closed) implements Event {

    public static KlineEvent of(Symbol symbol, Interval interval, Kline kline, boolean closed, long businessTs) {
        return new KlineEvent(EventIds.next(), businessTs, symbol, interval, kline, closed);
    }
}
