package com.ciaozn.alphatrader.gateway.binance;

/**
 * Exponential reconnect backoff (FR-GW-02): 1s, 2s, 4s ... capped at 60s.
 * Reset on every successful connection.
 */
public final class BackoffPolicy {

    private final long initialMs;
    private final long maxMs;
    private long currentMs;

    public BackoffPolicy() {
        this(1000L, 60_000L);
    }

    public BackoffPolicy(long initialMs, long maxMs) {
        this.initialMs = initialMs;
        this.maxMs = maxMs;
        this.currentMs = initialMs;
    }

    public long nextDelayMillis() {
        long delay = currentMs;
        currentMs = Math.min(currentMs * 2, maxMs);
        return delay;
    }

    public void reset() {
        currentMs = initialMs;
    }
}
