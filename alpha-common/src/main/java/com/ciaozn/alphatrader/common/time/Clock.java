package com.ciaozn.alphatrader.common.time;

import java.time.Instant;

/**
 * The single source of "now" for all components (FR-EN-03).
 * Live/paper uses {@link SystemClock}; backtest uses {@link VirtualClock} advanced by data.
 * Components must inject this interface and never call System.currentTimeMillis() directly.
 */
public interface Clock {

    long nowMillis();

    default Instant now() {
        return Instant.ofEpochMilli(nowMillis());
    }
}
