package com.ciaozn.alphatrader.app.alert;

/**
 * One alert's trip from the bus to wherever it ends up (T401).
 *
 * <p>An interface rather than Spring's {@code JavaMailSender} directly, for the same reason every
 * other boundary in this system is one: without it {@link AlertDispatcher} could only be tested by
 * standing up a mail server, and "alerting is broken" would then be discovered by an operator rather
 * than by CI. The two implementations - {@link SmtpAlertTransport} and {@link LoggingAlertTransport} -
 * are both production code; neither exists for the tests.
 *
 * <p><b>Why {@code send} throws instead of returning a status.</b> "The mail did not go" has no quiet
 * form: {@link AlertDispatcher} catches whatever escapes and downgrades to the log, so the only way an
 * implementation can swallow a failure is by lying - exactly the trap {@code ExchangeGateway} avoids
 * by throwing {@code ExchangeUnreachableException} rather than returning an empty order list. A
 * returned {@code false} would be read later as "nothing happened" instead of "nobody was told".
 */
public interface AlertTransport {

    /**
     * Delivers one alert. Called off the event-loop thread, never on it.
     *
     * @param subject already formatted, including severity and rule id
     * @param body    already formatted, including any collapsed-repeat count and the bus timestamps
     */
    void send(String subject, String body);
}
