package com.ciaozn.alphatrader.app.risk;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import com.ciaozn.alphatrader.app.config.RiskPipelines;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.risk.RiskGate;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.RiskRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * Turns a posted {@code alpha.risk} block into a live rule set (T404, FR-RK-09 / SEC-03), with the
 * second confirmation FR-SEC-03 requires and an audit trail for every accepted change.
 *
 * <p><b>Validate first, swap second.</b> The new pipeline is built and its rules constructed before
 * {@link RiskGate#reload} is ever called, and the rules validate their own parameters in their
 * constructors. So an out-of-range edit is refused as a whole and the previous pipeline stays in force
 * - never a partial application, which is the failure mode that makes hot reload dangerous: a subset of
 * the new limits would be a rule set no one wrote.
 *
 * <p><b>The swap runs on the engine thread.</b> Building and validating the pipeline is pure and
 * happens here; applying it and announcing it are handed to {@link EventEngine#runOnLoop}, so nothing
 * changes the gate while a signal is being decided. {@code gate.reload} is itself safe from any thread
 * (a volatile reference), and the loop is where the audit event is published from, so "what the gate
 * uses" and "what we said we changed it to" cannot come apart.
 *
 * <p><b>The audit is a {@code RiskAlertEvent}, not a new table.</b> It is the channel the rest of the
 * system already routes to the log and to email (FR-RK-08 / OP-03), and DESIGN §11 froze the business
 * schema at six tables (取舍 15) - an audit row would be a seventh whose only reader is a human, while
 * the alert reaches the same human with the same detail and no migration. The event is INFO: a
 * deliberate configuration change is not an incident, but it is a fact worth being able to find.
 */
public final class RiskReloadService {

    /** Rule id carried by the audit alert; it names the reload rather than any risk level. */
    public static final String RULE_RELOADED = "RK-09-risk-reloaded";

    /** How long the caller waits for the loop to apply the swap before reporting it as not applied. */
    private static final java.time.Duration RELOAD_APPLY_TIMEOUT = java.time.Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(RiskReloadService.class);

    private final RiskGate gate;
    private final Portfolio portfolio;
    private final EventEngine engine;
    private final Clock clock;

    public RiskReloadService(RiskGate gate, Portfolio portfolio, EventEngine engine, Clock clock) {
        this.gate = gate;
        this.portfolio = portfolio;
        this.engine = engine;
        this.clock = clock;
    }

    /**
     * Attempts the reload. Never throws for an expected refusal - the result says what happened.
     *
     * @param risk    the new {@code alpha.risk} block; its compact constructors enforce the same
     *                inequalities startup does
     * @param confirm FR-SEC-03's second confirmation. Must be true; a default of false exists so that
     *                a client which forgets the flag is refused rather than silently changing the
     *                account's limits
     */
    public RiskReloadResult reload(AlphaProperties.Risk risk, boolean confirm) {
        if (!confirm) {
            log.warn("Risk reload refused: confirm was not set");
            return RiskReloadResult.refused("reload refused: this changes the account's risk limits,"
                    + " so the request must carry confirm=true (FR-SEC-03)");
        }
        if (risk == null) {
            return RiskReloadResult.refused("reload refused: the request body must be an alpha.risk block");
        }
        if (!engine.isRunning()) {
            return RiskReloadResult.refused("reload refused: the event engine is not running, so there is"
                    + " no thread to apply the change or announce it on");
        }

        RiskPipeline replacement;
        try {
            replacement = RiskPipelines.of(risk, portfolio);
        } catch (IllegalArgumentException e) {
            // The rule constructors refused a parameter; the gate has not been touched.
            log.warn("Risk reload refused, configuration invalid: {}", e.getMessage());
            return RiskReloadResult.refused("reload refused, previous pipeline still in force: "
                    + e.getMessage());
        }

        List<String> signalRules = ids(replacement.signalRules());
        List<String> orderRules = ids(replacement.orderRules());
        warnAboutObserverRules(replacement);

        // Waits for the loop to apply the swap before answering (T404). "Accepted" and "in force" are
        // different claims, and an operator who reloads and immediately checks behaviour would have
        // been reading a state that was still on its way. The wait is bounded: a stuck loop must not
        // hang an HTTP thread, and the outcome is then reported as not applied rather than assumed.
        java.util.concurrent.CountDownLatch applied = new java.util.concurrent.CountDownLatch(1);
        engine.runOnLoop(() -> {
            try {
                gate.reload(replacement);
                engine.publish(RiskAlertEvent.of(RULE_RELOADED, RiskAlertEvent.Severity.INFO,
                        "risk pipeline reloaded by an operator: signal rules " + signalRules
                                + ", order rules " + orderRules
                                + "; only signals arriving after this instant see the new rules",
                        clock.nowMillis()));
            } finally {
                applied.countDown();
            }
        });
        boolean inForce;
        try {
            inForce = applied.await(RELOAD_APPLY_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the risk reload to apply", e);
        }
        if (!inForce) {
            return new RiskReloadResult(false,
                    "the engine did not apply the reload within " + RELOAD_APPLY_TIMEOUT.toSeconds()
                            + "s; the previous rules are still in force",
                    signalRules, orderRules);
        }
        log.info("Risk reload applied by an operator: signal rules {}, order rules {}", signalRules, orderRules);
        return new RiskReloadResult(true,
                "applied on the engine thread; the new pipeline decides every signal from now on",
                signalRules, orderRules);
    }

    /**
     * The one thing a reload cannot fix, said out loud rather than left to surprise someone: rules that
     * also observe the bus were registered once at startup, so a replaced breaker loses its fill feed.
     * See {@link RiskGate#reload}.
     */
    private static void warnAboutObserverRules(RiskPipeline replacement) {
        boolean hasObserver = Stream.concat(replacement.signalRules().stream(), replacement.orderRules().stream())
                .anyMatch(EventHandler.class::isInstance);
        if (hasObserver) {
            log.warn("Reloaded pipeline contains a bus-observer rule (e.g. the circuit breaker): it will"
                    + " decide from the current account on each signal, but its fill-driven state is lost,"
                    + " because handler registration is fixed at startup. Restart to retune it fully.");
        }
    }

    private static List<String> ids(List<? extends RiskRule> rules) {
        return rules.stream().map(RiskRule::ruleId).toList();
    }
}
