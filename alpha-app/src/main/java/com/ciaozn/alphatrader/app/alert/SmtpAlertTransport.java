package com.ciaozn.alphatrader.app.alert;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

/**
 * The real destination: a SMTP submission through Spring's {@code JavaMailSender} (T401, FR-OP-03).
 *
 * <p><b>Why this builds its own {@code MimeMessage} instead of using the convenience overloads.</b>
 * The injector insists on UTF-8 in two different places ({@code setDefaultEncoding} and the helper's
 * charset), because every detail string in this system contains Chinese - rule names, severity wording,
 * the recompute messages - and a half-configured message mangles them into {@code ???} at exactly the
 * moment somebody needs to read it. The hacked {@code setDefaultEncoding} alone is not enough: it
 * covers the encoding the encoder guesses from the message's own headers, not necessarily the body's.
 *
 * <p><b>Why there is no {@code sent}/{@code failed} return value.</b> See {@link AlertTransport}: the
 * only honest outcomes are "delivered" and "threw", and anything thrown here - {@code MailException}
 * for SMTP refused, authentication rejected, or the socket timing out - is caught one layer up and
 * downgraded to a log line, which is what keeps a dead mail relay from becoming a dead trading engine.
 *
 * <p><b>Why the "to" and "from" addresses live here rather than in the properties themselves.</b>
 * Nothing else sends mail, so putting them anywhere else would just spread one configuration over two
 * places that can disagree - and a mail with an absent {@code From} is rejected by the relay with an
 * error that reads like a network problem.
 */
public final class SmtpAlertTransport implements AlertTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpAlertTransport.class);

    private final JavaMailSender mailSender;
    private final String from;
    private final String to;

    public SmtpAlertTransport(JavaMailSender mailSender, String from, String to) {
        if (mailSender == null) {
            throw new IllegalArgumentException("mailSender must not be null: this transport IS the mail sender");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("from must be set: SMTP relays reject a message with no sender,"+
                    " and the error reads like a network failure rather than a missing address");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("to must be set: an alert with no recipient has been sent"
                    + " somewhere else entirely, which is worse than not sending it");
        }
        this.mailSender = mailSender;
        this.from = from;
        this.to = to;
    }

    @Override
    public void send(String subject, String body) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(from);
            helper.setSubject(subject);
            helper.setText(body);
        } catch (Exception e) {
            // Message construction failing is a programming error, not a delivery failure, but either
            // way the dispatcher's reaction is the same - log and carry on - so it leaves this class
            // as an exception rather than as a quiet return.
            throw new IllegalStateException("Cannot build alert mail for " + to, e);
        }
        mailSender.send(message);
        log.info("Alert mail sent to {}: {}", to, subject);
    }
}
