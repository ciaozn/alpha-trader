package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the status numbers come from, and from which thread (T402).
 *
 * <p>The confinement of {@link Portfolio} is why this class exists, so that is mostly what these tests are
 * about: the factory has to read the book <em>on the loop thread</em>, and it has to read nothing at all
 * when there is no loop to read it. Both properties are invisible to a test that only asserts the numbers
 * - an implementation reading straight from the caller's thread would produce exactly the same values until
 * the day it interleaved with a fill - so one case here parks the loop thread and watches what the polling
 * thread is given instead.
 */
class StatusSnapshotFactoryTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private VirtualClock clock;
    private Portfolio portfolio;
    private EngineHeartbeat heartbeat;
    private DailyRealizedPnl daily;
    private EventEngine engine;

    @BeforeEach
    void setUp() {
        clock = new VirtualClock(T0);
        portfolio = new Portfolio(new BigDecimal("10000"));
        heartbeat = new EngineHeartbeat(clock);
        daily = new DailyRealizedPnl(portfolio, clock);
        engine = new EventEngine(EventJournal.noop(), clock);
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private GatewayHealthProbe probeAnswering(long latencyMillis) {
        GatewayHealthProbeTest.Answering answering = new GatewayHealthProbeTest.Answering(clock);
        answering.takesMillis = latencyMillis;
        GatewayHealthProbe probe = new GatewayHealthProbe(answering, clock);
        probe.probeOnce();
        return probe;
    }

    private StatusSnapshotFactory factory(GatewayHealthProbe probe, String mode) {
        return new StatusSnapshotFactory(engine, portfolio, heartbeat, daily, probe, clock, mode, TIMEOUT);
    }

    private void startObserving() {
        engine.registerHandler(heartbeat);
        engine.registerHandler(daily);
        engine.start();
    }

    private void trade() {
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("2"), BigDecimal.ZERO);
        portfolio.mark(BTC, new BigDecimal("120"));
    }

    @Test
    void aRunningEngineAnswersWithPositionsEquityHeartbeatAndMode() {
        trade();
        startObserving();
        clock.advanceTo(T0 + 1_000L);
        heartbeat.onEvent(TimerEvent.of("tick", T0 + 1_000L), event -> { });

        // Latency zero: the probe advances the shared virtual clock by whatever it "took", and this
        // case is about the engine's own readings, not about coupling them to that number.
        StatusSnapshot snapshot = factory(probeAnswering(0L), "paper").snapshot();

        assertThat(snapshot.mode()).isEqualTo("paper");
        assertThat(snapshot.healthy()).isTrue();
        assertThat(snapshot.note()).isNull();
        assertThat(snapshot.account().equity()).isEqualByComparingTo("10040");
        assertThat(snapshot.account().cash()).isEqualByComparingTo("10000");
        assertThat(snapshot.account().unrealizedPnl()).isEqualByComparingTo("40");
        assertThat(snapshot.account().realizedPnlTotal()).isEqualByComparingTo("0");
        assertThat(snapshot.account().realizedPnlToday()).isEqualByComparingTo("0");
        assertThat(snapshot.account().totalNotional()).isEqualByComparingTo("240");
        assertThat(snapshot.positions()).hasSize(1);
        assertThat(snapshot.positions().get(0).symbol()).isEqualTo("BTCUSDT.PERP");
        assertThat(snapshot.positions().get(0).direction()).isEqualTo("LONG");
        assertThat(snapshot.positions().get(0).qty()).isEqualByComparingTo("2");
        assertThat(snapshot.positions().get(0).markPrice()).isEqualByComparingTo("120");
        assertThat(snapshot.positions().get(0).notional()).isEqualByComparingTo("240");
        assertThat(snapshot.engine().eventsObserved()).isEqualTo(1);
        assertThat(snapshot.engine().sinceLastEventMillis()).isZero();
    }

    /**
     * The confinement test. With the loop thread parked inside a handler, a factory that read the book on
     * its caller's thread would still answer with perfect numbers - so the assertion is that it answers
     * with none and says why, both ways round.
     */
    @Test
    void theAnswerIsReadOnTheLoopThreadAndNotOnWhicheverThreadAsked() throws Exception {
        trade();
        AtomicBoolean released = new AtomicBoolean(false);
        CountDownLatch entered = new CountDownLatch(1);
        engine.registerHandler(heartbeat);
        engine.registerHandler((event, publisher) -> {
            entered.countDown();
            // The loop wakes its thread by interrupting it, so this has to survive interrupts to be a
            // block at all - CountDownLatch.await here returns the moment the next task is queued, and
            // the test would then prove nothing.
            while (!released.get()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    // deliberately swallowed: blocking is the point of this handler
                }
            }
        });
        engine.start();
        try {
            engine.publish(TimerEvent.of("block", T0));
            assertThat(entered.await(5, TimeUnit.SECONDS))
                    .as("the loop thread is parked inside a handler").isTrue();

            StatusSnapshotFactory impatient = new StatusSnapshotFactory(engine, portfolio, heartbeat,
                    daily, probeAnswering(0L), clock, "paper", Duration.ofMillis(150));

            StatusSnapshot starved = callFrom(impatient, "polling-thread");
            assertThat(starved.account())
                    .as("no numbers were read, because only the loop thread may read them")
                    .isNull();
            assertThat(starved.note()).contains("did not read state within");

            released.set(true);
            assertThat(callFrom(factory(probeAnswering(0L), "paper"), "polling-thread")
                    .account().unrealizedPnl()).isEqualByComparingTo("40");
        } finally {
            released.set(true);
        }
    }

    @Test
    void anEngineThatIsNotRunningGivesANumberlessAnswerThatSaysSo() {
        trade();
        // Never started: reporting the last values would look like a live system that happens to be
        // idle, which is the one reading an operator must never be handed.
        StatusSnapshot snapshot = factory(probeAnswering(1L), "paper").snapshot();

        assertThat(snapshot.healthy()).isFalse();
        assertThat(snapshot.note()).contains("not running");
        assertThat(snapshot.engine().alive()).isFalse();
        assertThat(snapshot.engine().lastEventMillis()).isNull();
        assertThat(snapshot.account()).isNull();
        assertThat(snapshot.positions()).isEmpty();
    }

    @Test
    void theGatewaySectionKeepsAnsweringEvenWhenTheEngineCannot() {
        // The probe has a thread of its own, so what it knows stays true and stays readable - reporting
        // the engine's problem as the exchange's would send somebody looking for an outage that isn't.
        StatusSnapshot snapshot = factory(probeAnswering(31L), "live").snapshot();

        assertThat(snapshot.gateway().state()).isEqualTo("CONNECTED");
        assertThat(snapshot.gateway().latencyMillis()).isEqualTo(31L);
        assertThat(snapshot.gateway().lastProbeMillis()).isEqualTo(T0 + 31L);
    }

    @Test
    void latencyAndStateAreReportedForTheSameProbe() {
        startObserving();

        StatusSnapshot snapshot = factory(probeAnswering(19L), "paper").snapshot();

        assertThat(snapshot.gateway().state()).isEqualTo("CONNECTED");
        assertThat(snapshot.gateway().connected()).isTrue();
        assertThat(snapshot.gateway().latencyMillis()).isEqualTo(19L);
        assertThat(snapshot.gateway().sinceLastProbeMillis()).isZero();
        assertThat(snapshot.gateway().detail()).isNull();
    }

    @Test
    void anUnreachableExchangeMakesTheWholeDocumentUnhealthy() {
        startObserving();
        clock.advanceTo(T0 + 5_000L);
        heartbeat.onEvent(TimerEvent.of("tick", T0 + 5_000L), event -> { });

        GatewayHealthProbe probe = new GatewayHealthProbe(brokenGateway(), clock);
        probe.probeOnce();

        StatusSnapshot snapshot = factory(probe, "live").snapshot();

        assertThat(snapshot.gateway().connected()).isFalse();
        assertThat(snapshot.gateway().state()).isEqualTo("UNREACHABLE");
        assertThat(snapshot.gateway().latencyMillis()).isNull();
        assertThat(snapshot.gateway().detail()).contains("connection reset");
        assertThat(snapshot.engine().alive()).as("the loop itself is fine").isTrue();
        assertThat(snapshot.healthy())
                .as("one boolean for whoever polls: the loop works but nothing can trade")
                .isFalse();
    }

    @Test
    void nothingObservedYetIsNotPretendedToBeAHeartbeat() {
        startObserving();

        StatusSnapshot snapshot = factory(probeAnswering(0L), "paper").snapshot();

        assertThat(snapshot.engine().eventsObserved()).isZero();
        assertThat(snapshot.engine().lastEventMillis()).isNull();
        assertThat(snapshot.engine().sinceLastEventMillis()).isNull();
        assertThat(snapshot.engine().alive()).isTrue();
    }

    private static ExchangeGateway brokenGateway() {
        return new GatewayHealthProbeTest.Answering(new VirtualClock(T0)) {
            @Override
            public List<OpenOrder> queryOpenOrders() {
                throw new ExchangeUnreachableException("connection reset");
            }
        };
    }

    /** Asks from a thread that is not the loop's, which is what every real caller is. */
    private StatusSnapshot callFrom(StatusSnapshotFactory factory, String threadName) {
        StatusSnapshot[] holder = new StatusSnapshot[1];
        Thread poll = new Thread(() -> holder[0] = factory.snapshot(), threadName);
        poll.start();
        try {
            poll.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(holder[0]).as("the polling thread got an answer").isNotNull();
        return holder[0];
    }
}
