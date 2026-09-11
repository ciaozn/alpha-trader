package com.ciaozn.alphatrader.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cadence of the two periodic jobs the online modes run (T318/T318b).
 *
 * <p>Both are exposed rather than hard-coded because the right value depends on what the system is
 * doing: reconciliation at 60s is the spec default and also the widest window in which a divergence
 * can go unnoticed, and snapshots at 60s is a storage-vs-resolution trade anyone running this for
 * months will want to make themselves.
 */
@ConfigurationProperties(prefix = "alpha.online")
public record OnlineProperties(Duration reconcilePeriod, Duration snapshotPeriod) {

    public static final Duration DEFAULT_RECONCILE_PERIOD = Duration.ofSeconds(60);
    public static final Duration DEFAULT_SNAPSHOT_PERIOD = Duration.ofSeconds(60);

    public OnlineProperties {
        if (reconcilePeriod == null) {
            reconcilePeriod = DEFAULT_RECONCILE_PERIOD;
        }
        if (snapshotPeriod == null) {
            snapshotPeriod = DEFAULT_SNAPSHOT_PERIOD;
        }
    }
}
