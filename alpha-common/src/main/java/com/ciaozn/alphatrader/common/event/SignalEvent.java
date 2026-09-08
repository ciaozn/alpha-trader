package com.ciaozn.alphatrader.common.event;

import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * The ONLY output a strategy may produce (FR-ST-05). Carries intent + strength;
 * the risk engine converts it into a concrete quantity (FR-RK-07).
 */
@JsonTypeName("signal")
public record SignalEvent(
        long eventId,
        long timestamp,
        String strategyId,
        Symbol symbol,
        Direction direction,
        double strength,
        String reason) implements Event {

    public static SignalEvent of(String strategyId, Symbol symbol, Direction direction,
                                 double strength, String reason, long businessTs) {
        return new SignalEvent(EventIds.next(), businessTs, strategyId, symbol, direction, strength, reason);
    }
}
