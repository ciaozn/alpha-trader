package com.ciaozn.alphatrader.common.model;

/**
 * OMS order lifecycle (FR-EX-01):
 * NEW -> SUBMITTED -> PARTIALLY_FILLED -> FILLED / CANCELED / REJECTED
 */
public enum OrderStatus {
    NEW,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED;
    }
}
