package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Decides what the loop is allowed to do about an alert, and never does the slow part itself (T401,
 * FR-OP-03).
 *
 * <p><b>{@link #dispatch} runs on the event-loop thread and must stay cheap.</b> Sending mail is a
 * blocking network round trip whose worst case is not "slow" but "a TCP connection that never comes
 * back", and on the loop that is a stalled strategy, a stalled risk gate and a missed bar. So this
 * class only throttles, formats and hands a {@code Runnable} to an {@link Executor}; everything past
 * the queue happens on someone else's thread. This is the same reasoning that gave the order path its
 * own worker thread ({@code OrderSender}) - the difference is that alerts are allowed to fail quietly
 * rather than be retried, so there is no outbox here.
 *
 * <p><b>The text is built before the hand-off, not inside it.</b> {@link RiskAlertEvent} is immutable,
 * so formatting it now costs nothing later, and doing it on this side means the worker never touches
 * anything the loop owns - a version of this class that read the portfolio inside the task would be
 * reading engine-thread state from another thread, which is precisely what every other component here
 * avoids.
 *
 * <p><b>Every failure ends in a log line, never in an exception reaching the caller.</b> A rejecting
 * executor (the queue is full or the bean is shutting down) and a failing transport are different
 * incidents with the same consequence: this alert was not delivered. Throwing would not change that -
 * the next caller is {@code EventEngine.dispatch}, which catches per handler and logs anyway - it
 * would only lose the alert-specific explanation. Nothing here may stop the engine, and nothing here
 * retries: the next occurrence of the same rule is itself a report, and reconciliation and the risk
 * pipeline are what actually notice that something stayed broken.
 */
public final class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final AlertProperties properties;
    private final AlertTransport transport;
    private final AlertThrottle throttle;
    private final Executor executor;
    private final Clock clock;

    public AlertDispatcher(AlertProperties properties, AlertTransport transport, AlertThrottle throttle,
                           Executor executor, Clock clock) {
        this.properties = properties;
        this.transport = transport;
        this.throttle = throttle;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Called from {@link EmailAlertHandler} on the engine thread. Returns without having performed any
     * IO: mail leaves this process from {@code executor}'s thread or not at all.
     */
    public void dispatch(RiskAlertEvent alert) {
        if (!properties.active()) {
            return;
        }
        AlertThrottle.Decision decision =
                throttle.evaluate(alert.ruleId(), alert.severity(), clock.nowMillis());
        if (!decision.send()) {
            // Not even a log line per suppressed alert: a rule that fires 50 times in a window would
            // otherwise produce 50 lines about how little one says. The next mail carries the count.
            log.debug("Alert {} suppressed inside its aggregation window", alert.ruleId());
            return;
        }
        String subject = subject(alert);
        String body = body(alert, decision.collapsed());
        try {
            executor.execute(() -> deliver(alert, subject, body));
        } catch (RejectedExecutionException e) {
            // Saturated queue or shutdown. Dropping is the only option that keeps the promise this
            // class makes to the loop - never block, never stall - so it is said loudly instead.
            log.error("Alert {} was dropped: the mail executor refused it ({}); this alert was NOT sent",
                    alert.ruleId(), e.getMessage());
        }
    }

    private void deliver(RiskAlertEvent alert, String subject, String body) {
        try {
            transport.send(subject, body);
        } catch (RuntimeException e) {
            // The one place an alert may end without leaving the process: delivery failure downgrades
            // to this log line, which is the fallback FR-OP-03 asks for. Nothing propagates - a dead
            // relay must not become a dead engine.
            log.error("Failed to send alert {} ({}): {}; falling back to this log line - SUBJECT: {} BODY: {}",
                    alert.ruleId(), alert.severity(), e.getMessage(), subject, body);
        }
    }

    /**
     * Subject first, because it is what the mail client shows: severity and rule id answer "do I need
     * to open this now" without opening it. UTC throughout - the operator reading this may be looking
     * at exchange logs that are UTC too, and <em>local</em> time is nobody's time.
     */
    private String subject(RiskAlertEvent alert) {
        return "[alpha][" + alert.severity() + "] " + alert.ruleId();
    }

    private String body(RiskAlertEvent alert, long collapsed) {
        StringBuilder text = new StringBuilder()
                .append("severity : ").append(alert.severity()).append('\n')
                .append("rule     : ").append(alert.ruleId()).append('\n')
                .append("detail   : ").append(alert.detail()).append('\n')
                .append("eventId  : ").append(alert.eventId()).append('\n')
                .append("bus time : ").append(format(alert.timestamp())).append('\n')
                .append("observed : ").append(format(clock.nowMillis())).append('\n');
        if (collapsed > 0) {
            // The count belongs to the window that just closed, so it is phrased in the past tense:
            // asserting "same did N times" about this mail would be false, this occurrence is NEW.
            text.append("collapsed: ").append(collapsed)
                    .append(" further alert(s) of this rule were suppressed in the last ")
                    .append(properties.aggregationWindow()).append('\n');
        }
        return text.toString();
    }

    private static String format(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toString();
    }
}
