package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reachability and latency, judged from answers rather than from silence (T402).
 *
 * <p>Two kinds of "no answer" matter here and they must not be confused, because they ask whoever is awake
 * at three in the morning to do completely different things: {@code ExchangeUnreachableException} means the
 * exchange did not respond and something is genuinely wrong, while any other failure means this deployment
 * cannot even pose the question - a market-data-only gateway has no signed client, and calling that an
 * outage would have paper mode reporting a permanent disconnection.
 *
 * <p>The gateway is a stub, so nothing here touches the network, and the clock is virtual, so the latency
 * a call "took" is a number this test chose rather than however busy CI's machine happened to be.
 */
class GatewayHealthProbeTest {

    private static final long T0 = 1_700_000_000_000L;

    /**
     * Only {@code queryOpenOrders} matters to this component. Everything else refuses, so that a probe
     * reaching for some other method fails the test instead of silently succeeding.
     */
    static abstract class StubGateway implements ExchangeGateway {

        @Override
        public void connect(GatewayConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void subscribeKline(Symbol symbol, Interval interval) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OrderAck placeOrder(OrderRequestEvent request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelOrder(String clientOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSnapshot queryAccount() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Position> queryPositions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }

    /** Answers after advancing the clock by {@code takesMillis}, which is what "latency" means here. */
    static class Answering extends StubGateway {

        final AtomicInteger calls = new AtomicInteger();
        final VirtualClock clock;
        volatile long takesMillis;

        Answering(VirtualClock clock) {
            this.clock = clock;
        }

        @Override
        public List<OpenOrder> queryOpenOrders() {
            calls.incrementAndGet();
            if (takesMillis > 0) {
                clock.advanceTo(clock.nowMillis() + takesMillis);
            }
            return List.of();
        }
    }

    @Test
    void aSuccessfulProbeReportsTheRoundTripItTook() {
        VirtualClock clock = new VirtualClock(T0);
        Answering gateway = new Answering(clock);
        gateway.takesMillis = 137L;
        GatewayHealthProbe probe = new GatewayHealthProbe(gateway, clock);

        probe.probeOnce();

        GatewayHealthProbe.Health health = probe.health();
        assertThat(health.state()).isEqualTo(GatewayHealthProbe.State.CONNECTED);
        assertThat(health.connected()).isTrue();
        assertThat(health.latencyMillis()).isEqualTo(137L);
        assertThat(health.observedAtMillis()).isEqualTo(T0 + 137L);
        assertThat(health.detail()).isNull();
    }

    @Test
    void anExchangeThatDoesNotAnswerIsAnOutage() {
        VirtualClock clock = new VirtualClock(T0);
        StubGateway gateway = new StubGateway() {
            @Override
            public List<OpenOrder> queryOpenOrders() {
                throw new ExchangeUnreachableException("read timed out");
            }
        };
        GatewayHealthProbe probe = new GatewayHealthProbe(gateway, clock);

        probe.probeOnce();

        GatewayHealthProbe.Health health = probe.health();
        assertThat(health.state()).isEqualTo(GatewayHealthProbe.State.UNREACHABLE);
        assertThat(health.connected()).isFalse();
        assertThat(health.latencyMillis()).as("there is no round trip to time").isNull();
        assertThat(health.detail()).contains("read timed out");
    }

    @Test
    void aGatewayThatCannotAnswerAtAllIsNotReportedAsDisconnected() {
        // Market data only: there is no signed client, so this gateway throws IllegalStateException. That
        // is a statement about configuration, not an incident, and the status page carries both facts.
        VirtualClock clock = new VirtualClock(T0);
        StubGateway gateway = new StubGateway() {
            @Override
            public List<OpenOrder> queryOpenOrders() {
                throw new IllegalStateException("This gateway is connected without credentials");
            }
        };
        GatewayHealthProbe probe = new GatewayHealthProbe(gateway, clock);

        probe.probeOnce();

        GatewayHealthProbe.Health health = probe.health();
        assertThat(health.state()).isEqualTo(GatewayHealthProbe.State.UNAVAILABLE);
        assertThat(health.connected()).isFalse();
        assertThat(health.detail()).contains("IllegalStateException").contains("credentials");
    }

    @Test
    void aRecoveringExchangeClearsTheOutageOnTheNextProbe() {
        VirtualClock clock = new VirtualClock(T0);
        StubGateway flapping = new StubGateway() {
            final AtomicInteger attempts = new AtomicInteger();

            @Override
            public List<OpenOrder> queryOpenOrders() {
                if (attempts.incrementAndGet() == 1) {
                    throw new ExchangeUnreachableException("connection reset");
                }
                return List.of();
            }
        };
        GatewayHealthProbe probe = new GatewayHealthProbe(flapping, clock);
        probe.probeOnce();
        assertThat(probe.health().state()).isEqualTo(GatewayHealthProbe.State.UNREACHABLE);

        probe.probeOnce();

        // Health is whatever the last probe found, not a latch flipped once: a link that came back has to
        // show as back without anyone restarting anything.
        assertThat(probe.health().state()).isEqualTo(GatewayHealthProbe.State.CONNECTED);
    }

    @Test
    void beforeTheFirstProbeNothingIsClaimed() {
        VirtualClock clock = new VirtualClock(T0);
        GatewayHealthProbe probe = new GatewayHealthProbe(new Answering(clock), clock);

        GatewayHealthProbe.Health health = probe.health();

        // UNKNOWN rather than "connected": the page has to distinguish "not asked yet" from "asked and
        // answered", or it will report every link as healthy at startup, before anything has been tested.
        assertThat(health.state()).isEqualTo(GatewayHealthProbe.State.UNKNOWN);
        assertThat(health.connected()).isFalse();
        assertThat(health.latencyMillis()).isNull();
        assertThat(health.detail()).contains("not probed");
    }

    @Test
    void theFirstRunWaitsForTheGatewayRatherThanRecordingAnOutageItHasNotYetHadTimeToAvoid() {
        // The gateway connects asynchronously during startup; probing at second zero would rate the link
        // before it had finished opening, and nothing would correct that answer until the next period.
        VirtualClock clock = new VirtualClock(T0);
        Answering gateway = new Answering(clock);
        GatewayHealthProbe probe = new GatewayHealthProbe(gateway, clock);

        probe.start(Duration.ofSeconds(60));
        try {
            assertThat(gateway.calls.get()).as("no probe runs during the first period").isZero();
        } finally {
            probe.close();
        }
    }

    @Test
    void closingStopsAskingButKeepsTheLastAnswer() throws Exception {
        VirtualClock clock = new VirtualClock(T0);
        Answering gateway = new Answering(clock);
        GatewayHealthProbe probe = new GatewayHealthProbe(gateway, clock);

        probe.start(Duration.ofMillis(10));
        Thread.sleep(80);
        probe.close();
        probe.close(); // idempotent, because Spring's shutdown hooks are not always run once either

        int callsAtClose = gateway.calls.get();
        Thread.sleep(80);

        assertThat(gateway.calls.get()).as("nothing is asked of the exchange after close").isEqualTo(callsAtClose);
        assertThat(probe.health().state()).isEqualTo(GatewayHealthProbe.State.CONNECTED);
    }
}
