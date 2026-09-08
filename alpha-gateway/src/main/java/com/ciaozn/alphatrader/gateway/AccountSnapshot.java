package com.ciaozn.alphatrader.gateway;

import java.math.BigDecimal;

/** Point-in-time account state from the exchange. Exchange is the source of truth. */
public record AccountSnapshot(
        BigDecimal totalEquity,
        BigDecimal availableMargin,
        BigDecimal marginRatio) {
}
