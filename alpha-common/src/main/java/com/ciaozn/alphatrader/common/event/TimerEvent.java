package com.ciaozn.alphatrader.common.event;

import com.fasterxml.jackson.annotation.JsonTypeName;

/** Periodic system tick (reconciliation, heartbeat, journal flush). Scheduled via the engine (FR-EN-04). */
@JsonTypeName("timer")
public record TimerEvent(
        long eventId,
        long timestamp,
        String name) implements Event {

    public static TimerEvent of(String name, long businessTs) {
        return new TimerEvent(EventIds.next(), businessTs, name);
    }
}
