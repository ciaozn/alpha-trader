package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Same-rule aggregation (FR-OP-03): one {@code ruleId} gets one mail per {@code window}; the repeats
 * it swallowed are counted and carried into the next mail that rule is allowed to send.
 *
 * <p><b>Why the count rides on the NEXT mail instead of producing its own.</b> The alternative - send
 * the first alert immediately and summarize the window afterwards - emails twice per window, which is
 * the thing the requirement is trying to prevent: a flapping rule doubles its own traffic and trains
 * whoever receives it to skim. Appending "there were N more like this" to the mail that the next
 * occurrence triggers keeps exactly one mail per window while still telling the reader that the
 * problem repeated, which is the only fact a summary mail would have added.
 *
 * <p><b>Why the first occurrence is not deferred to the end of the window.</b> Delaying every alert by
 * five minutes buys one thing - a complete count in the first mail - and costs the only thing an alert
 * is for: finding out now. Neither an exchange round trip failures nor trip-breaker needs waiting.
 *
 * <p><b>Why CRITICAL bypasses the window entirely and also leaves no trace in it.</b> A CRITICAL alert
 * is, by the pipeline's own definition (FR-RK-08 and every rule that emits it), either the account in
 * danger, an input that cannot be trusted, or an exchange whose answer we do not have - none of which
 * should be summarized into somebody else's mail. Not recording them matters too: had a CRITICAL opened
 * a window, the WARNING that follows would be swallowed inside the loudest alert's silence.
 *
 * <p><b>Not thread-safe, and does not need to be.</b> It is called from exactly one place -
 * {@link AlertDispatcher#dispatch(RiskAlertEvent)} - which runs on the event-loop thread, the same
 * confinement {@code Portfolio} relies on.
 */
public final class AlertThrottle {

    /** Whether this occurrence may be mailed, and how many of its siblings died for it. */
    public record Decision(boolean send, long collapsed) {

        public static final Decision SUPPRESS = new Decision(false, 0L);
    }

    private final long windowMillis;
    private final Map<String, Window> windows = new HashMap<>();

    public AlertThrottle(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("aggregation window must be positive, got " + window);
        }
        this.windowMillis = window.toMillis();
    }

    /**
     * @param ruleId   what makes two alerts "alike"; the pipeline already owns a stable id per rule
     * @param severity {@code CRITICAL} is never aggregated
     * @param nowMillis taken from the injected clock, so a throttler is testable at a fixed instant
     */
    public Decision evaluate(String ruleId, RiskAlertEvent.Severity severity, long nowMillis) {
        if (ruleId == null || ruleId.isBlank()) {
            // The gate's every rejection carries an id; an alert without one is a bug in whatever
            // published it. Sending it anyway keeps a nameless alert from being swallowed twice over,
            // and it cannot poison another rule's window because it never enters the map.
            return new Decision(true, 0L);
        }
        if (severity == RiskAlertEvent.Severity.CRITICAL) {
            return new Decision(true, 0L);
        }
        Window window = windows.get(ruleId);
        if (window == null) {
            windows.put(ruleId, new Window(nowMillis));
            return new Decision(true, 0L);
        }
        if (nowMillis - window.openedAtMillis < windowMillis) {
            window.collapsed++;
            return Decision.SUPPRESS;
        }
        // The window has expired: this occurrence opens a new one and carries the body count of the
        // old. Resetting here rather than on a timer is what keeps this class free of threads - the
        // next alert is the only moment anyone cares whether the window is open.
        long collapsed = window.collapsed;
        windows.put(ruleId, new Window(nowMillis));
        return new Decision(true, collapsed);
    }

    /** Mutable by design: replacing it wholesale per window is clearer than four fields plus a flag. */
    private static final class Window {

        private final long openedAtMillis;
        private long collapsed;

        Window(long openedAtMillis) {
            this.openedAtMillis = openedAtMillis;
        }
    }
}
