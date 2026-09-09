package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;

/**
 * Why one signal did not become an order. Carries exactly what {@link RiskAlertEvent} and the
 * interception record need (FR-RK-08) and nothing else - in particular no portfolio snapshot:
 * the facts the rule was handed <em>are</em> that snapshot, and re-reading the book later would
 * record an account state that may already have moved on.
 */
public record RiskRejection(
        String ruleId,
        RiskRule.Level level,
        RiskAlertEvent.Severity severity,
        String detail) {

    public RiskRejection {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("A rejection must name the rule that produced it");
        }
        if (level == null) {
            throw new IllegalArgumentException("A rejection must carry a level, got null for " + ruleId);
        }
        if (severity == null) {
            throw new IllegalArgumentException("A rejection must carry a severity, got null for " + ruleId);
        }
    }
}
