package com.ciaozn.alphatrader.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Where the online modes keep their business tables (T319/T408): orders, fills, signals, snapshots,
 * interceptions.
 *
 * <p>A separate prefix from {@code alpha.backtest.data} because the two are not the same thing: that
 * one is a read-only corpus of historical bars, this one is the writable record of what this process
 * did. Sharing a URL would mean a backtest run and a live run writing into each other's history.
 *
 * <p><b>SQLite locally, MySQL on the server</b> (DESIGN §11): one {@code jdbc-url} switches between
 * them, and {@link Dialect} is how the code tells which one it was given. The dialect is derived from
 * the URL prefix rather than configured separately - two settings that must agree is one setting too
 * many, and a mismatch would show up as a driver error at the first write instead of at startup.
 *
 * <p>Credentials are separate fields because they only apply to MySQL: SQLite is a file. A server
 * deployment passes them through the environment ({@code ALPHA_DB_USER} / {@code ALPHA_DB_PASSWORD}),
 * which is the same rule the API keys follow - nothing secret lives in a committed file.
 */
@ConfigurationProperties(prefix = "alpha.store")
public record StoreProperties(String jdbcUrl, String username, String password) {

    /** SQLite by default: local development needs no server, and the schema is dialect-neutral. */
    public static final String DEFAULT_JDBC_URL = "jdbc:sqlite:data/trading.db";

    public enum Dialect {
        SQLITE,
        MYSQL
    }

    public StoreProperties {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = DEFAULT_JDBC_URL;
        }
        username = blankToNull(username);
        password = blankToNull(password);
    }

    /**
     * Which driver the URL asks for, decided here so an unsupported URL fails at startup with a
     * message naming the URL rather than at the first query with a driver-not-found stack trace.
     */
    public Dialect dialect() {
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:sqlite:")) {
            return Dialect.SQLITE;
        }
        if (url.startsWith("jdbc:mysql:")) {
            return Dialect.MYSQL;
        }
        throw new IllegalStateException("Unsupported alpha.store.jdbc-url '" + jdbcUrl
                + "': this application carries drivers for SQLite (jdbc:sqlite:...) and MySQL (jdbc:mysql:...) only");
    }

    /** MySQL needs credentials; SQLite must not be given any (they would be silently ignored). */
    public void requireCredentialsIfNeeded() {
        if (dialect() == Dialect.MYSQL && (username == null || password == null)) {
            throw new IllegalStateException("alpha.store.username and alpha.store.password are required for MySQL"
                    + " (set ALPHA_DB_USER / ALPHA_DB_PASSWORD in the environment)");
        }
    }

    public String effectiveUsername() {
        return username;
    }

    public String effectivePassword() {
        return password;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
