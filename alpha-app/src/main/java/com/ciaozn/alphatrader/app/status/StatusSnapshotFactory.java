package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds the status answer <em>on the engine thread</em> (T402, FR-OP-02).
 *
 * <p><b>The whole reason this class exists is the confinement of {@link Portfolio}:</b> it is documented
 * as "not thread-safe by design - it is only ever touched on the single event-engine thread", and the
 * status endpoint answers on a servlet thread. Reading the book from there would not merely risk a stale
 * number - {@code equity()} walks every position map summing BigDecimals into a local, so a concurrent
 * {@code applyFill} can leave it reading a position that is half updated and cannot yet be described as
 * before or after the fill. {@link EventEngine#runOnLoop(Runnable)} is the seam the engine provides for
 * exactly this ("gather the facts on your own thread, then apply them here"), used in reverse: the facts
 * are gathered where they live and handed back whole.
 *
 * <p><b>A timeout, not a blocking wait.</b> {@code runOnLoop} only runs while the loop is running, so a
 * stopped engine would leave a {@code future.get()} hanging for the lifetime of the request - and an HTTP
 * handler that hangs is how a monitoring check becomes a thread-pool exhaustion. The loop drains its task
 * queue between events, so this answer normally comes back within one bar's worth of microseconds; the
 * timeout only ever fires when there is nothing left to answer it.
 *
 * <p><b>When the engine is down nothing is read.</b> Returning last-known numbers would be friendlier and
 * wrong - the answer would describe a state nobody can act on, indistinguishable from a live one. The
 * degraded snapshot says what it could not read and why, which is also why {@code healthy} exists: one
 * boolean that means "trust the rest of this document".
 */
public final class StatusSnapshotFactory {

    private static final Logger log = LoggerFactory.getLogger(StatusSnapshotFactory.class);

    private final EventEngine engine;
    private final Portfolio portfolio;
    private final EngineHeartbeat heartbeat;
    private final DailyRealizedPnl daily;
    private final GatewayHealthProbe probe;
    private final Clock clock;
    private final String mode;
    private final Duration timeout;

    public StatusSnapshotFactory(EventEngine engine, Portfolio portfolio, EngineHeartbeat heartbeat,
                                 DailyRealizedPnl daily, GatewayHealthProbe probe, Clock clock,
                                 String mode, Duration timeout) {
        this.engine = engine;
        this.portfolio = portfolio;
        this.heartbeat = heartbeat;
        this.daily = daily;
        this.probe = probe;
        this.clock = clock;
        this.mode = mode;
        this.timeout = timeout;
    }

    /**
     * A snapshot taken at one instant on the loop thread, or a degraded one saying why not. Never throws:
     * an endpoint that answers with 500 is telling the operator less than one that answers with an
     * explanation.
     */
    public StatusSnapshot snapshot() {
        long nowMillis = clock.nowMillis();
        if (!engine.isRunning()) {
            return degraded(nowMillis, "the event engine is not running, so no state was read");
        }
        CompletableFuture<StatusSnapshot> future = new CompletableFuture<>();
        engine.runOnLoop(() -> {
            try {
                future.complete(readOnLoopThread());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Status snapshot timed out after {}: nothing read the state", timeout);
            return degraded(nowMillis, "the event engine did not read state within " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return degraded(nowMillis, "interrupted while waiting for the event engine");
        } catch (ExecutionException e) {
            log.error("Status snapshot failed on the engine thread", e.getCause());
            return degraded(nowMillis, "reading state failed: " + e.getCause());
        }
    }

    /** Runs on the loop thread: this is the only place in this package allowed to read the book. */
    private StatusSnapshot readOnLoopThread() {
        long nowMillis = clock.nowMillis();
        EngineHeartbeat.Reading reading = heartbeat.read(nowMillis);
        GatewayHealthProbe.Health health = probe.health();
        return new StatusSnapshot(mode, reasonNotToTrustThis(health) == null,
                reasonNotToTrustThis(health), nowMillis,
                new StatusSnapshot.EngineView(true, reading.lastEventMillis(),
                        reading.sinceLastEventMillis(), reading.eventsObserved()),
                gatewayView(health, nowMillis),
                accountView(),
                positions());
    }

    /**
     * What {@code healthy} means: nothing is known to be broken. It deliberately does <em>not</em> mean
     * "the loop saw an event recently" - with one bar an hour, silence is the normal state, and a boolean
     * that spent most of the hour false would be ignored the moment it mattered. The age of the last event
     * is reported alongside it precisely so whoever cares can judge the silence against their own bar
     * interval. Nor does {@code UNAVAILABLE} count against health: that state says this deployment cannot
     * ask the question at all (no credentials), which is a standing fact about how it was configured, not
     * an incident. Everything else that could make the document untrustworthy is said in {@code note}.
     */
    private static String reasonNotToTrustThis(GatewayHealthProbe.Health health) {
        return health.state() == GatewayHealthProbe.State.UNREACHABLE
                ? "the exchange is unreachable: " + health.detail()
                : null;
    }

    private StatusSnapshot.GatewayView gatewayView(GatewayHealthProbe.Health health, long nowMillis) {
        Long observedAt = health.observedAtMillis() == 0L ? null : health.observedAtMillis();
        return new StatusSnapshot.GatewayView(health.state().name(), health.connected(),
                health.latencyMillis(), observedAt,
                observedAt == null ? null : nowMillis - observedAt, health.detail());
    }

    private StatusSnapshot.AccountView accountView() {
        return new StatusSnapshot.AccountView(portfolio.equity(), portfolio.cash(),
                portfolio.unrealizedPnl(), portfolio.realizedPnl(), daily.today(),
                portfolio.totalNotional());
    }

    private List<StatusSnapshot.PositionView> positions() {
        return portfolio.openPositions().stream().map(this::view).toList();
    }

    private StatusSnapshot.PositionView view(Position position) {
        BigDecimal mark = portfolio.markOf(position.symbol());
        return new StatusSnapshot.PositionView(position.symbol().unified(),
                position.direction().name(), position.qty(), position.entryPrice(), mark,
                position.notional(mark), position.unrealizedPnl(mark));
    }

    /**
     * Nothing shared was read, so nothing shared is claimed - with one exception worth naming: the
     * gateway's health belongs to the probe's own thread, not the loop's, so it stays truthful even here.
     * Reporting the engine's problem as the exchange's would send whoever is woken at night looking for
     * an outage that does not exist.
     */
    private StatusSnapshot degraded(long nowMillis, String reason) {
        return new StatusSnapshot(mode, false, reason, nowMillis,
                new StatusSnapshot.EngineView(false, null, null, 0L),
                gatewayView(probe.health(), nowMillis),
                null, List.of());
    }
}
