package com.ciaozn.alphatrader.risk;

/**
 * One refusal, complete: what was asked for, what the account looked like when it was asked, and
 * which rule said no. FR-RK-08 in one type - "拦截记录可查询，包含命中的规则与当时账户状态".
 *
 * <p><b>The reason this type exists instead of persisting the {@code RiskAlertEvent} is the second
 * half of that sentence.</b> The alert carries {@code ruleId}, {@code severity} and a detail string,
 * and nothing about the account, so a store fed from the bus would be queryable, would name the rule,
 * and would still not answer "was that refusal right?" - the only question an interception record
 * exists for. It would also look complete while failing, because everything it does hold is true.
 *
 * <p>{@code facts} is the snapshot the rules were handed, taken once before any rule ran, so the
 * account state recorded here is the one the decision was made against and cannot have moved on
 * since (see {@link SignalFacts}). {@code rejection} is the short-circuit that ended it. Neither
 * half is optional and neither is derivable from the other, which is what a record of two components
 * says structurally.
 *
 * <p>Consequence for wiring: the gate writes this, not an observer on the bus. The facts are not on
 * the bus at all - they are taken inside one {@code onEvent} call and never published - so no handler
 * registration order could recover them.
 */
public record InterceptionRecord(SignalFacts facts, RiskRejection rejection) {

    /** Which rule refused - the key FR-RK-08's "按规则查询" is by. */
    public String ruleId() {
        return rejection.ruleId();
    }

    /** When the gate refused, on the same clock the snapshot was taken with. */
    public long timestamp() {
        return facts.nowMillis();
    }
}
