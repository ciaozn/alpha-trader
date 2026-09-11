package com.ciaozn.alphatrader.app.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The {@code alpha.alert} block (T401, FR-OP-03): who gets mailed, through which SMTP host, and how
 * long one rule keeps the floor to itself.
 *
 * <p><b>There is no {@code authCode} here, and that is deliberate.</b> QQ Mail authenticates SMTP with
 * an authorization code generated in the mailbox's settings, not with the login password; it is a
 * credential, so it is read straight from the {@code ALERT_SMTP_AUTH_CODE} environment variable in
 * {@link AlertConfig} and never bound from {@code application.yml}. A secret that can be bound from a
 * file is a secret one careless commit away from the repository (FR-SEC-01) - and unlike a missing
 * API key, a leaked authorization code lets someone send mail as the account, which is worse than
 * leaking read-only exchange rights.
 *
 * <p><b>Every field tolerates being absent, and every default is the safe one.</b> {@code enabled}
 * defaults to false so that cloning the repository and running {@code paper} mails nobody; the rest
 * fall back to QQ Mail's published settings. This mirrors {@code OnlineProperties}: defaults live in
 * the record rather than in yml, so a block nobody wrote still binds, and the numbers have exactly
 * one definition.
 *
 * <p><b>The window is a {@code Duration}, not an int of minutes.</b> "5 minutes" written as
 * {@code 5} next to {@code Duration.ofMinutes} waiting to be read as milliseconds is the same class
 * of error as {@code maxNotionalFraction} being read as a percentage, and unlike a wrong fraction it
 * fails silently: 5ms of aggregation is no aggregation at all.
 *
 * @param aggregationWindow how long one {@code ruleId} suppresses its repeats; must be positive
 */
@ConfigurationProperties(prefix = "alpha.alert")
public record AlertProperties(
        Boolean enabled,
        String to,
        String from,
        String username,
        Duration aggregationWindow,
        String host,
        Integer port,
        Boolean ssl) {

    public static final Duration DEFAULT_AGGREGATION_WINDOW = Duration.ofMinutes(5);
    /** QQ Mail's SMTP submission with implicit TLS; there is no reason to guess another. */
    public static final String DEFAULT_HOST = "smtp.qq.com";
    public static final int DEFAULT_PORT = 465;

    public AlertProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
        if (aggregationWindow == null) {
            aggregationWindow = DEFAULT_AGGREGATION_WINDOW;
        }
        if (aggregationWindow.isZero() || aggregationWindow.isNegative()) {
            throw new IllegalArgumentException("alpha.alert.aggregation-window must be positive, got "
                    + aggregationWindow + ": a window that never opens would collapse every repeat of a"
                    + " rule into one mail for the lifetime of the process, and the operator would learn"
                    + " about a recurring problem exactly once");
        }
        if (host == null || host.isBlank()) {
            host = DEFAULT_HOST;
        }
        if (port == null) {
            port = DEFAULT_PORT;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("alpha.alert.port must be in [1, 65535], got " + port);
        }
        if (ssl == null) {
            ssl = Boolean.TRUE;
        }
    }

    /** True only when this mode actually tries to talk to an SMTP server. */
    public boolean active() {
        return Boolean.TRUE.equals(enabled);
    }
}
