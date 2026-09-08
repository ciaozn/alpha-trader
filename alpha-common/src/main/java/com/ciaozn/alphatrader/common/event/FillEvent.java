package com.ciaozn.alphatrader.common.event;

import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.math.BigDecimal;

/** One execution (partial or full). Returns to the engine so every component sees the same truth. */
@JsonTypeName("fill")
public record FillEvent(
        long eventId,
        long timestamp,
        String clientOrderId,
        Symbol symbol,
        Side side,
        BigDecimal price,
        BigDecimal qty,
        BigDecimal fee) implements Event {

    public static FillEvent of(String clientOrderId, Symbol symbol, Side side,
                               BigDecimal price, BigDecimal qty, BigDecimal fee, long businessTs) {
        return new FillEvent(EventIds.next(), businessTs, clientOrderId, symbol, side, price, qty, fee);
    }
}
