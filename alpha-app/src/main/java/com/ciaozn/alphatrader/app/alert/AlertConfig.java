package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Alert assembly (T401): one mail sender, one transport choice, one thread that carries the mail.
 *
 * <p><b>Online profiles only.</b> Backtest replays history - the same breaker can trip on four hundred
 * consecutive bars - and download touches neither engine nor exchange. Giving those profiles an
 * assembly that writes to a mailbox would turn every replay into a mail storm, so the whole
 * configuration carries {@code @Profile({"paper","live"})} for the reason {@code OnlineWiringConfig}
 * does: one unannotated bean would silently half-build them.
 *
 * <p><b>The authorization code comes from the environment, never from yml</b> (FR-SEC-01). QQ Mail
 * authenticates SMTP with an authorization code generated in the mailbox settings; it is a credential
 * like {@code BINANCE_API_SECRET}, and this project's rule for those is that they are read with
 * {@code System.getenv} at the point of use - exactly how {@code StartupWiring} reads the API key and
 * secret. A value that can be bound from {@code application.yml} can also be committed.
 */
@Configuration
@Profile({"paper", "live"})
public class AlertConfig {

    /** Where {@code AlphaTraderApplication} would read it from too; see {@code .env.example}. */
    static final String AUTH_CODE_ENV = "ALERT_SMTP_AUTH_CODE";

    private static final Logger log = LoggerFactory.getLogger(AlertConfig.class);

    /** Every SMTP step has a deadline - see {@link #alertMailSender} for what a hang would cost. */
    private static final int SMTP_TIMEOUT_MILLIS = 10_000;
    /** Beyond the throttler, this is the second bound on how much mail a broken relay can stack up. */
    private static final int QUEUE_CAPACITY = 256;

    /**
     * The SMTP client for QQ Mail (smtp.qq.com:465, implicit TLS).
     *
     * <p>{@code @ConditionalOnMissingBean} because this is one definition among several possible ones:
     * any other {@code JavaMailSender} in the context - including Boot's own from {@code spring.mail.*}
     * - wins, rather than producing two senders and an ambiguity nobody can diagnose from the error
     * message. What that means operationally is written in {@code AlertProperties}: the {@code
     * alpha.alert} host and port describe how <em>this</em> configuration would connect.
     *
     * <p><b>Why connect, read and write timeouts are set explicitly.</b> The default is to wait
     * forever, and this thread is shared by every rule in the system: one relay that accepts a TCP
     * connection and then goes quiet would occupy the single mailer slot indefinitely, and every
     * subsequent alert would queue behind it and then be dropped by the executor instead of being
     * delivered. A mail we could not send in ten seconds is a mail that gets logged here and now.
     */
    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    JavaMailSender alertMailSender(AlertProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        sender.setUsername(properties.username());
        sender.setPassword(System.getenv(AUTH_CODE_ENV));
        sender.setDefaultEncoding("UTF-8");
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", Boolean.TRUE.equals(properties.ssl()));
        props.put("mail.smtp.starttls.enable", "false"); // 465 is implicit TLS; STARTTLS belongs on 587
        props.put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MILLIS);
        props.put("mail.smtp.timeout", SMTP_TIMEOUT_MILLIS);
        props.put("mail.smtp.writetimeout", SMTP_TIMEOUT_MILLIS);
        sender.setJavaMailProperties(props);
        return sender;
    }

    /**
     * Choosing the transport is choosing whether enabling alerting without credentials means "quiet"
     * or "noisy failures". It is {@link LoggingAlertTransport}: the operator sees every alert in the
     * journal, at WARN, and knows that no mail is leaving - which is one ticket (grant SMTP access)
     * rather than two (why is nothing arriving, and why is the log full of TLS errors).
     */
    @Bean
    AlertTransport alertTransport(AlertProperties properties, JavaMailSender mailSender) {
        if (!properties.active()) {
            log.info("Alerting disabled: no transport will be used (alpha.alert.enabled=false)");
            return (subject, body) -> { /* deliberately nothing: disabled means disabled */ };
        }
        String authCode = System.getenv(AUTH_CODE_ENV);
        if (authCode == null || authCode.isBlank()) {
            log.warn("Alerting enabled but {} is not set: alerts go to the LOG ONLY, no mail will be sent",
                    AUTH_CODE_ENV);
            return new LoggingAlertTransport();
        }
        return new SmtpAlertTransport(mailSender, properties.from(), properties.to());
    }

    /**
     * The thread the mail goes out on - the one thing standing between an SMTP outage and a stalled
     * event loop.
     *
     * <p><b>One thread, daemon, bounded queue, abort on overflow.</b> One is enough (alerting is not a
     * throughput path, and it also serialises the mails so they arrive in the order they were raised);
     * daemon so it cannot hold the JVM open after the engine has let go. The capacity and the policy
     * are the interesting part: an unbounded queue would let a slow relay accumulate until the heap
     * filled, and {@code CallerRunsPolicy} - the usual answer to "queue full" - would run the send on
     * the event-loop thread, which is the exact hazard this whole package exists to avoid. Aborting is
     * therefore the only policy that keeps the promise the dispatcher makes, and the dispatcher logs
     * every alert it loses this way.
     */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService alertExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                task -> {
                    Thread thread = new Thread(task, "alert-mailer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Bean
    AlertThrottle alertThrottle(AlertProperties properties) {
        return new AlertThrottle(properties.aggregationWindow());
    }

    @Bean
    AlertDispatcher alertDispatcher(AlertProperties properties, AlertTransport transport,
                                    AlertThrottle throttle, ExecutorService alertExecutor,
                                    Clock clock) {
        return new AlertDispatcher(properties, transport, throttle, alertExecutor, clock);
    }
}
