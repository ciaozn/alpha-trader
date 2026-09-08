package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.time.Clock;

import java.util.function.LongSupplier;

/**
 * Exchange server-time calibration (spec edge case 8). Signed requests die if local
 * clock drifts; we measure the offset at startup and alarm beyond the threshold.
 * The fetcher is injected so this is testable without network.
 */
public final class TimeSync {

    public static final long DEFAULT_THRESHOLD_MS = 1000L;

    private final LongSupplier serverTimeFetcher;
    private final Clock clock;
    private final long thresholdMs;

    public TimeSync(LongSupplier serverTimeFetcher, Clock clock) {
        this(serverTimeFetcher, clock, DEFAULT_THRESHOLD_MS);
    }

    public TimeSync(LongSupplier serverTimeFetcher, Clock clock, long thresholdMs) {
        this.serverTimeFetcher = serverTimeFetcher;
        this.clock = clock;
        this.thresholdMs = thresholdMs;
    }

    /** Positive offset means the exchange clock is AHEAD of local. */
    public long calibrateOffset() {
        long localBefore = clock.nowMillis();
        long server = serverTimeFetcher.getAsLong();
        long localAfter = clock.nowMillis();
        // midpoint of the round trip approximates "when the server stamped the time"
        long localMidpoint = (localBefore + localAfter) / 2;
        return server - localMidpoint;
    }

    public boolean isDriftExceeded(long offsetMs) {
        return Math.abs(offsetMs) > thresholdMs;
    }
}
