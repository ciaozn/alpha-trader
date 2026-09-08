package com.ciaozn.alphatrader.common.time;

/** Wall clock for paper/live modes. This is the ONLY place System.currentTimeMillis() is allowed. */
public final class SystemClock implements Clock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
