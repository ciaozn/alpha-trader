package com.ciaozn.alphatrader.gateway;

/**
 * Exponential reconnect backoff (FR-GW-02): 1s, 2s, 4s ... capped at 60s, reset after a
 * successful connection.
 *
 * <p>Lives in the shared gateway package rather than under {@code binance} because both exchange
 * adapters reconnect the same way - the policy is a property of networks, not of an API.
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
