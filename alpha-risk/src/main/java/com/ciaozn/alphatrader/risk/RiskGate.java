package com.ciaozn.alphatrader.risk;

/**
 * Placeholder for the P3 risk pipeline - the single hard gate between signals and orders
 * (spec FR-RK-01). Implemented in plan phase P3 (T-references: plan.md §6).
 *
 * <p>Design contract (already fixed, do not redesign in P3):
 * rules form an ordered pipeline; any rejection blocks the order and emits
 * a RiskAlertEvent; the gate also converts signal intent into concrete quantity (FR-RK-07).
 */
public final class RiskGate {

    private RiskGate() {
        throw new UnsupportedOperationException("Risk pipeline is implemented in P3");
    }
}
