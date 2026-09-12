package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.common.model.Symbol;

/**
 * OKX instId ({@code BTC-USDT-SWAP}) to and from the unified internal form (T406, FR-GW-04).
 *
 * <p>Kept apart from {@link Symbol} itself: the unified model must not learn exchange dialects, and
 * {@code Symbol.okx()} already produces this form for the outbound direction. This class exists for
 * the inbound one, and throws rather than guessing when the instrument is not a perpetual swap - a
 * spot or dated-future id mapped onto a perpetual would be traded with the wrong contract.
 */
final class OkxSymbols {

    private OkxSymbols() {
    }

    static Symbol toUnified(String instId) {
        if (instId == null || !instId.endsWith("-SWAP")) {
            throw new IllegalArgumentException("Not an OKX perpetual swap: " + instId);
        }
        String[] parts = instId.split("-");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Unexpected OKX instId shape: " + instId);
        }
        return Symbol.perp(parts[0], parts[1]);
    }
}
