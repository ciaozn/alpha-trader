package com.ciaozn.alphatrader.common.event;

import com.fasterxml.jackson.annotation.JsonTypeName;

/** Raised when the risk pipeline blocks something or a system-level risk condition fires (FR-RK-08). */
@JsonTypeName("riskAlert")
public record RiskAlertEvent(
        long eventId,
        long timestamp,
        String ruleId,
        Severity severity,
        String detail) implements Event {

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    public static RiskAlertEvent of(String ruleId, Severity severity, String detail, long businessTs) {
        return new RiskAlertEvent(EventIds.next(), businessTs, ruleId, severity, detail);
    }
}
