package com.ciaozn.alphatrader.common.event;

import com.ciaozn.alphatrader.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;

/** Latest trade price tick. Used for mark-price checks (price deviation rule). */
@JsonTypeName("ticker")
public record TickerEvent(
        long eventId,
        long timestamp,
        Symbol symbol,
        BigDecimal price) implements Event {

    public static TickerEvent of(Symbol symbol, BigDecimal price, long businessTs) {
        return new TickerEvent(EventIds.next(), businessTs, symbol, price);
    }
}
