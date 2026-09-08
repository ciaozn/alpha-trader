package com.ciaozn.alphatrader.common.time;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic clock for backtest: time only moves when the data feeder advances it.
 * Same input -> same time sequence -> bit-identical results (NFR-04).
 */
public final class VirtualClock implements Clock {

    private final AtomicLong current;

    public VirtualClock(long startMillis) {
        this.current = new AtomicLong(startMillis);
    }

    @Override
    public long nowMillis() {
        return current.get();
    }

    /** Advance time; never moves backwards. */
    public void advanceTo(long millis) {
        current.updateAndGet(cur -> Math.max(cur, millis));
    }
}
