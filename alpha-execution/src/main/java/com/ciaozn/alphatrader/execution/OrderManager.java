package com.ciaozn.alphatrader.execution;

/**
 * Placeholder for the P3 OMS - order state machine (NEW -> SUBMITTED -> ... -> FILLED),
 * clientOrderId idempotency (FR-EX-02) and periodic reconciliation against the
 * exchange as source of truth (FR-EX-04). Implemented in plan phase P3.
 */
public final class OrderManager {

    private OrderManager() {
        throw new UnsupportedOperationException("OMS is implemented in P3");
    }
}
