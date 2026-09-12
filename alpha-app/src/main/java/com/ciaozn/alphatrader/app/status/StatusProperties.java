package com.ciaozn.alphatrader.app.status;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The two knobs {@code /api/status} answers with (T402).
 *
 * <p>Both are bound rather than hard-coded because the honest value depends on the deployment: a
 * trading-enabled process to which REST latency matters probes more often than one that only needs to
 * know whether the stream is alive, and a monitoring poller on a slow VPS finds 2s of patience too
 * tight. Defaults are chosen so that the endpoint never waits long enough to be mistaken for a hung
 * request, and never probes often enough to notice itself on the exchange's request budget.
 *
 * @param probePeriod     how often the reachability probe asks the exchange something; must be positive
 * @param snapshotTimeout how long {@code /api/status} waits for the engine thread to read the book.
 *                        {@link StatusSnapshotFactory} returns a degraded answer on timeout rather than
 *                        reading state itself, so this bound can never turn into a torn read
 */
@ConfigurationProperties(prefix = "alpha.status")
public record StatusProperties(Duration probePeriod, Duration snapshotTimeout) {

    public static final Duration DEFAULT_PROBE_PERIOD = Duration.ofSeconds(60);
    public static final Duration DEFAULT_SNAPSHOT_TIMEOUT = Duration.ofSeconds(2);

    public StatusProperties {
        if (probePeriod == null) {
            probePeriod = DEFAULT_PROBE_PERIOD;
        }
        if (probePeriod.isZero() || probePeriod.isNegative()) {
            throw new IllegalArgumentException("alpha.status.probe-period must be positive, got "
                    + probePeriod + ": a probe with no interval between runs is a tight loop against the"
                    + " exchange, and every unanswered call is a request toward the rate limit");
        }
        if (snapshotTimeout == null) {
            snapshotTimeout = DEFAULT_SNAPSHOT_TIMEOUT;
        }
        if (snapshotTimeout.isZero() || snapshotTimeout.isNegative()) {
            throw new IllegalArgumentException("alpha.status.snapshot-timeout must be positive, got "
                    + snapshotTimeout + ": with no time to answer, every request would come back"
                    + " degraded and say nothing at all");
        }
    }
}
