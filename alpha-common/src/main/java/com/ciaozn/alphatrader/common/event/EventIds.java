package com.ciaozn.alphatrader.common.event;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global monotonic event id allocator (FR-EN-02).
 * Ids are unique within one JVM process; the journal also records business timestamps,
 * so cross-process ordering is reconstructed from (timestamp, eventId).
 */
public final class EventIds {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private EventIds() {
    }

    public static long next() {
        return SEQUENCE.incrementAndGet();
    }

    /** Test/backtest hook: reset sequence for deterministic replay. */
    public static void reset() {
        SEQUENCE.set(0);
    }
}
