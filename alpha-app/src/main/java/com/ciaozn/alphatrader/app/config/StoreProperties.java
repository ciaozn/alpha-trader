package com.ciaozn.alphatrader.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the online modes keep their business tables (T319): orders, fills, signals, snapshots,
 * interceptions.
 *
 * <p>A separate prefix from {@code alpha.backtest.data} because the two are not the same thing: that
 * one is a read-only corpus of historical bars, this one is the writable record of what this process
 * did. Sharing a URL would mean a backtest run and a live run writing into each other's history.
 */
@ConfigurationProperties(prefix = "alpha.store")
public record StoreProperties(String jdbcUrl) {

    /** SQLite by default: local development needs no server, and the schema is dialect-neutral. */
    public static final String DEFAULT_JDBC_URL = "jdbc:sqlite:data/trading.db";

    public StoreProperties {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = DEFAULT_JDBC_URL;
        }
    }
}
