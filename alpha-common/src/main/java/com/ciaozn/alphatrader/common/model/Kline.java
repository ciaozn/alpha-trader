package com.ciaozn.alphatrader.common.model;

import java.math.BigDecimal;

/**
 * One standardized OHLCV bar. All monetary/quantity values use BigDecimal everywhere
 * in the system - floating point money math is forbidden.
 */
public record Kline(
        long openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        long closeTime) {
}
