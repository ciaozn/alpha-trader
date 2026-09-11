package com.ciaozn.alphatrader.app.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where alerts go when there is no mailbox to send them to (T401).
 *
 * <p>This is the transport {@link AlertConfig} installs when alerting is switched on but no SMTP
 * authorization code was supplied - which is the state every fresh clone starts in, and the state a
 * misconfigured deployment stays in. The alternative, letting {@link SmtpAlertTransport} be built with
 * a blank password and fail on every send, loses the alert twice: once to the SMTP error, and once to
 * the log's TLS handshake noise burying the actual message.
 *
 * <p><b>Why the whole body goes to the log rather than just "an alert fired".</b> The body is the only
 * place the rule id, the severity and the detail meet; logging the subject alone would leave whoever
 * greps the journal at 3am reading a count of suppressed repeats with no way to find out which rule.
 * The same log lines are what the operator reads while waiting for SMTP access to be granted, so they
 * have to be as complete as the mail would have been.
 */
public final class LoggingAlertTransport implements AlertTransport {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertTransport.class);

    @Override
    public void send(String subject, String body) {
        // WARN and not DEBUG: this line is the alert for this deployment. Routing it below the
        // configured level would make enabling alerting without SMTP credentials look exactly like
        // a system that never alerts.
        log.warn("ALERT (no SMTP configured, log only) - {}\n{}", subject, body);
    }
}
