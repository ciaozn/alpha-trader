package com.ciaozn.alphatrader.app.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends one throwaway alert so the operator learns the channel works before it matters (T503, P5-1).
 *
 * <p><b>A drill, not a health check.</b> An SMTP configuration can be complete and still not deliver:
 * the authorization code can be stale, the address misspelled, the provider silently dropping mail to
 * a folder nobody opens. None of that shows up until the first real alert - which is by definition
 * the moment you were relying on it. So the drill sends a real message through the real transport and
 * reports what happened.
 *
 * <p><b>A failed drill is a result, not an exception.</b> The caller gets {@link Outcome#FAILED} with
 * the reason, because the interesting information is the reason: "address rejected" and "connection
 * timed out" have nothing in common but the word failure.
 */
public final class AlertDrill {

    private static final Logger log = LoggerFactory.getLogger(AlertDrill.class);

    public enum Outcome {
        SENT,
        SKIPPED,
        FAILED
    }

    public record Result(Outcome outcome, String detail) {

        public boolean ok() {
            return outcome == Outcome.SENT || outcome == Outcome.SKIPPED;
        }
    }

    private final AlertProperties properties;
    private final AlertTransport transport;

    public AlertDrill(AlertProperties properties, AlertTransport transport) {
        this.properties = properties;
        this.transport = transport;
    }

    public Result drill() {
        if (!properties.active()) {
            return new Result(Outcome.SKIPPED,
                    "alpha.alert.enabled=false: no alert would be delivered, and that is by configuration");
        }
        try {
            transport.send("Alpha Trader drill",
                    "This is an alert drill, sent on purpose so the channel is known to work.\n"
                            + "If you are reading it, alerting is live. No action is needed.\n");
            log.info("Alert drill delivered to {}", properties.to());
            return new Result(Outcome.SENT, "drill delivered to " + properties.to());
        } catch (RuntimeException e) {
            log.error("Alert drill failed", e);
            return new Result(Outcome.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
