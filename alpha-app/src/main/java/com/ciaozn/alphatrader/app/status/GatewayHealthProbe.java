package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Whether the exchange is answering, and how long it takes when it does (T402, FR-OP-02).
 *
 * <p><b>Why this class exists at all.</b> {@code ExchangeGateway} deliberately exposes no notion of
 * connection state - its job is to publish market data and place orders, and adding an {@code isConnected}
 * to it would mean editing every gateway for a monitoring convenience. But "the process is up" and "the
 * process can still reach Binance" are different facts: the streaming subscription reconnects on its own,
 * silently, so the one visible symptom of a dead link is that the bars stop arriving - which for a 1h bar
 * is an hour of nothing. So connectivity is measured here by asking, rather than inferred from silence.
 *
 * <p><b>Why {@code queryOpenOrders} and not something invented for this.</b> It is a read the system
 * already makes every reconciliation period, it needs no introduction to the interface, and it answers
 * the same question reconciliation asks: can this process still read what the exchange holds. At one call
 * per {@code probe-period} (60s by default) it is ~1.4k requests a day against a budget in the hundreds of
 * thousands, and it is deliberately not adaptive - a probe that tried harder when the network was bad
 * would add load to exactly the failure it is reporting.
 *
 * <p><b>{@link State#UNAVAILABLE} exists because "not answering" has two very different causes.</b> A
 * gateway connected for market data only has no signed client and throws {@code IllegalStateException} on
 * any private read; that is not an outage, and reporting it as one would have every paper deployment
 * screaming about disconnection. So: {@link ExchangeUnreachableException} - the gateway's own signal for
 * "the exchange did not answer" - is {@link State#UNREACHABLE}; anything else is a probe that this
 * deployment cannot answer, said plainly in {@code detail} instead of dressed up as an outage.
 *
 * <p><b>Daemon thread, off the loop, never throwing.</b> The probe is blocking IO and therefore belongs
 * nowhere near the engine thread for exactly the reason {@code OrderSender} has its own worker. Whatever
 * escapes a probe becomes the health answer, never an exception: a scheduled task that throws would stop
 * being rescheduled, and the status page would freeze on whatever it last saw.
 */
public final class GatewayHealthProbe implements AutoCloseable {

    public enum State {
        /** The last probe got an answer. */
        CONNECTED,
        /** The gateway reported that the exchange did not answer. */
        UNREACHABLE,
        /** This deployment's gateway cannot answer the probe at all (e.g. no credentials). */
        UNAVAILABLE,
        /** Nothing has been probed yet. */
        UNKNOWN
    }

    /**
     * One probe's answer, published as a whole so a reader can never see a latency from one call beside
     * a timestamp from another.
     *
     * @param latencyMillis null when there was no answer to time
     */
    public record Health(State state, boolean connected, Long latencyMillis,
                         long observedAtMillis, String detail) {

        static Health unknown(long nowMillis) {
            return new Health(State.UNKNOWN, false, null, nowMillis, "not probed yet");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GatewayHealthProbe.class);

    private final ExchangeGateway gateway;
    private final Clock clock;
    private volatile Health health = Health.unknown(0L);
    private volatile ScheduledExecutorService executor;

    public GatewayHealthProbe(ExchangeGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    /**
     * Begins probing. The first run waits a full period rather than running immediately: the gateway is
     * told to connect during startup and its connection is asynchronous, so probing at second zero would
     * record an outage against a link that simply had not finished opening yet - and the answer would
     * stand until somebody happened to poll after the first retry.
     */
    public synchronized void start(Duration period) {
        if (executor != null) {
            return;
        }
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-health-probe");
            thread.setDaemon(true);
            return thread;
        });
        pool.scheduleWithFixedDelay(this::probeSafely, period.toMillis(), period.toMillis(),
                TimeUnit.MILLISECONDS);
        executor = pool;
        log.info("Gateway health probe scheduled every {}", period);
    }

    public Health health() {
        return health;
    }

    /** One probe; package-private so it can be driven without waiting for a schedule. */
    void probeOnce() {
        long startedAtMillis = clock.nowMillis();
        try {
            gateway.queryOpenOrders();
            long finishedAtMillis = clock.nowMillis();
            health = new Health(State.CONNECTED, true, finishedAtMillis - startedAtMillis,
                    finishedAtMillis, null);
        } catch (ExchangeUnreachableException e) {
            health = new Health(State.UNREACHABLE, false, null, clock.nowMillis(), e.getMessage());
        } catch (RuntimeException e) {
            health = new Health(State.UNAVAILABLE, false, null, clock.nowMillis(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void probeSafely() {
        try {
            probeOnce();
        } catch (Throwable t) {
            // Nothing may kill the scheduled task: a probe that stopped being rescheduled would leave
            // the status endpoint reporting the health of a moment that has long passed.
            log.error("Gateway health probe failed", t);
            health = new Health(State.UNKNOWN, false, null, clock.nowMillis(),
                    "the probe itself failed: " + t);
        }
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
