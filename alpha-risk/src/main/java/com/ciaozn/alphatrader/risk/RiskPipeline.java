package com.ciaozn.alphatrader.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ordered rule set behind FR-RK-01: signals pass through it in configuration order and the
 * first objection ends the attempt. Two stages rather than one list, for the reason given on
 * {@link RiskRule} - the order-level and portfolio-level rules need a quantity that does not exist
 * until FR-RK-07 has run.
 *
 * <p>An empty pipeline is legal and means "no rules beyond sizing": the gate still refuses a symbol
 * with no cached trading rules and still rejects a quantity that rounds below the exchange minimum
 * (spec edge case 4). That is the configuration the backtest ran with until P3, and it is what a
 * unit test uses when it wants the gate's own behaviour rather than a rule's.
 *
 * <p>Order is part of the contract, not an implementation detail: a rule is reached only if every
 * rule before it passed, so a stateful rule (the frequency counter, the circuit breaker) sees
 * exactly the signals that survived its predecessors. Configuration therefore fixes the order and
 * this class preserves it - no sorting by level, id or severity, which would make the interception
 * record depend on a rule's name.
 */
public final class RiskPipeline {

    private final List<SignalRule> signalRules;
    private final List<OrderRule> orderRules;

    public static RiskPipeline empty() {
        return new RiskPipeline(List.of(), List.of());
    }

    public RiskPipeline(List<SignalRule> signalRules, List<OrderRule> orderRules) {
        // One id space across both stages: the interception record and the alert are keyed by ruleId
        // alone, so the same id in two stages would make a hit ambiguous about what actually fired.
        Map<String, RiskRule> byId = new LinkedHashMap<>();
        List<RiskRule> all = new ArrayList<>(signalRules);
        all.addAll(orderRules);
        for (RiskRule rule : all) {
            RiskRule previous = byId.putIfAbsent(rule.ruleId(), rule);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate risk rule id " + rule.ruleId()
                        + " (" + previous.getClass().getSimpleName() + " and "
                        + rule.getClass().getSimpleName() + "): alerts and interception records are "
                        + "keyed by id, so two rules may not share one");
            }
        }
        this.signalRules = List.copyOf(signalRules);
        this.orderRules = List.copyOf(orderRules);
    }

    /** First objection to the signal itself, before any quantity exists. */
    public Optional<RiskRejection> checkSignal(SignalFacts facts) {
        for (SignalRule rule : signalRules) {
            Optional<RiskRejection> rejection = rule.check(facts);
            if (rejection.isPresent()) {
                return Optional.of(attributed(rule, rejection.get()));
            }
        }
        return Optional.empty();
    }

    /** First objection to the concrete order, evaluated against the book as it would be afterwards. */
    public Optional<RiskRejection> checkOrder(OrderFacts facts) {
        for (OrderRule rule : orderRules) {
            Optional<RiskRejection> rejection = rule.check(facts);
            if (rejection.isPresent()) {
                return Optional.of(attributed(rule, rejection.get()));
            }
        }
        return Optional.empty();
    }

    public List<SignalRule> signalRules() {
        return signalRules;
    }

    public List<OrderRule> orderRules() {
        return orderRules;
    }

    /**
     * A rejection must name the rule that produced it. Without this, a copy-pasted rule reports
     * another rule's id: the order is still correctly blocked, the alert reads plausibly, and the
     * interception record points at a rule that never fired - the kind of error that is only found
     * by someone trying to explain a block to themselves at 3am.
     */
    private static RiskRejection attributed(RiskRule rule, RiskRejection rejection) {
        if (!rejection.ruleId().equals(rule.ruleId()) || rejection.level() != rule.level()) {
            throw new IllegalStateException("Rule " + rule.ruleId() + " (level " + rule.level()
                    + ") returned a rejection attributed to " + rejection.ruleId() + " (level "
                    + rejection.level() + ")");
        }
        return rejection;
    }
}
