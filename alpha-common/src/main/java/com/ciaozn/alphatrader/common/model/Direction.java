package com.ciaozn.alphatrader.common.model;

/** Signal direction: the strategy's intent (FR-ST-05: strategies output intent, not orders). */
public enum Direction {
    LONG,
    SHORT,
    /** Close any existing exposure in this symbol. */
    FLAT
}
