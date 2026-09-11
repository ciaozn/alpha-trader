package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the event loop is promised about alerting (T401), proved without a mail server: nothing here
 * opens a socket, and every thread used is either inline or one this test starts and stops.
 *
 * <p>Three properties carry the requirement, and each has a test that would fail if it were broken:
 * <ol>
 *   <li>the decision for every alert is made on the engine thread, but the mail is not sent there
 *       ({@code theEngineThreadDoesNotWaitForTheMailToLeave});</li>
 *   <li>a rule that fires repeatedly yields one mail, and the mail says how many there were
 *       ({@code fiveAlertsOfOneRuleInAWindowProduceOneMailWithTheCount});</li>
 *   <li>nothing that goes wrong while sending can reach the caller
 *       ({@code aTransportThatThrowsDoesNotReachTheCallerAndTheNextAlertStillGoes}).</li>
 * </ol>
 */
class AlertDispatcherTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** Every mail that left, in order. */
    static final class RecordingTransport implements AlertTransport {

        final List<String> subjects = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        volatile RuntimeException failure;

        @Override
        public void send(String subject, String body) {
            if (failure != null) {
                throw failure;
            }
            subjects.add(subject);
            bodies.add(body);
        }
    }

    static RiskAlertEvent alert(String ruleId, RiskAlertEvent.Severity severity, long ts) {
        return new RiskAlertEvent(1L, ts, ruleId, severity, "detail of " + ruleId);
    }

    private AlertProperties enabled() {
        return new AlertProperties(true, "to@example.com", "from@example.com", "user",
                WINDOW, "smtp.qq.com", 465, true);
    }

    /**
     * Same-thread executor: the standard trick for testing something whose whole point is that it runs
     * elsewhere - with the thread removed, everything else (throttling, formatting, degradation) can be
     * asserted without a race, and {@link AlertDispatcherTest#theEngineThreadDoesNotWaitForTheMailToLeave}
     * covers the threading separately.
     */
    private AlertDispatcher inline(RecordingTransport transport, VirtualClock clock) {
        return new AlertDispatcher(enabled(), transport, new AlertThrottle(WINDOW), Runnable::run, clock);
    }

    @Test
    void oneMailPerRuleInsideTheWindowAndTheNextOneCarriesTheCount() {
        RecordingTransport transport = new RecordingTransport();
        VirtualClock clock = new VirtualClock(T0);
        AlertDispatcher dispatcher = inline(transport, clock);

        dispatcher.dispatch(alert("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()));
        for (int i = 1; i <= 5; i++) {
            clock.advanceTo(T0 + i * 1_000L);
            dispatcher.dispatch(alert("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()));
        }

        assertThat(transport.subjects).hasSize(1);
        assertThat(transport.subjects.get(0)).isEqualTo("[alpha][WARNING] RK-03-order");

        clock.advanceTo(T0 + WINDOW.toMillis());
        dispatcher.dispatch(alert("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()));

        assertThat(transport.subjects).hasSize(2);
        assertThat(transport.bodies.get(1))
                .contains("collapsed: 5")
                .contains("suppressed in the last PT5M");
    }

    @Test
    void aCriticalIsSentEvenWhileItsOwnRuleIsBeingAggregated() {
        RecordingTransport transport = new RecordingTransport();
        VirtualClock clock = new VirtualClock(T0);
        AlertDispatcher dispatcher = inline(transport, clock);

        // Order matters here: the CRITICAL must get out even though the WARNING of the same rule has
        // already claimed this window - which is exactly why the throttler keeps CRITICAL out of it.
        dispatcher.dispatch(alert("EX-exchange-unreachable", RiskAlertEvent.Severity.WARNING, clock.nowMillis()));
        clock.advanceTo(T0 + 1_000L);
        dispatcher.dispatch(alert("EX-exchange-unreachable", RiskAlertEvent.Severity.CRITICAL, clock.nowMillis()));
        clock.advanceTo(T0 + 2_000L);
        dispatcher.dispatch(alert("EX-exchange-unreachable", RiskAlertEvent.Severity.WARNING, clock.nowMillis()));

        assertThat(transport.subjects).containsExactly(
                "[alpha][WARNING] EX-exchange-unreachable",
                "[alpha][CRITICAL] EX-exchange-unreachable");
    }

    @Test
    void disabledMeansNoMailAndNoWork() {
        RecordingTransport transport = new RecordingTransport();
        VirtualClock clock = new VirtualClock(T0);
        AlertProperties properties = new AlertProperties(false, "to@example.com", "from@example.com",
                "user", WINDOW, "smtp.qq.com", 465, true);
        AlertDispatcher dispatcher =
                new AlertDispatcher(properties, transport, new AlertThrottle(WINDOW), Runnable::run, clock);

        dispatcher.dispatch(alert("RK-05-breaker", RiskAlertEvent.Severity.CRITICAL, clock.nowMillis()));

        assertThat(transport.subjects).isEmpty();
    }

    @Test
    void theMailNamesWhatHappened() {
        RecordingTransport transport = new RecordingTransport();
        VirtualClock clock = new VirtualClock(T0);
        AlertDispatcher dispatcher = inline(transport, clock);

        RiskAlertEvent event = new RiskAlertEvent(42L, T0 - 5_000L, "RK-02-account",
                RiskAlertEvent.Severity.CRITICAL, "equity below the margin floor");
        dispatcher.dispatch(event);

        assertThat(transport.bodies.get(0))
                .contains("severity : CRITICAL")
                .contains("rule     : RK-02-account")
                .contains("detail   : equity below the margin floor")
                .contains("eventId  : 42")
                .contains("bus time : 2023-11-14T22:13:15Z")
                .contains("observed : 2023-11-14T22:13:20Z");
    }

    /**
     * The requirement "异步发送，绝不阻塞事件循环" as an executable statement: the transport blocks until
     * this test lets it go, so if the mail were being sent inline this call could never return. Asserting
     * that {@code dispatch} came back while the send is still parked is the whole point - the usual
     * "assert it eventually arrived" would pass just as well for a blocking implementation.
     */
    @Test
    void theEngineThreadDoesNotWaitForTheMailToLeave() throws Exception {
        CountDownLatch mailStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-mailer");
            thread.setDaemon(true);
            return thread;
        });

        try {
            VirtualClock clock = new VirtualClock(T0);
            AlertTransport slow = (subject, body) -> {
                mailStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                deliveries.incrementAndGet();
                delivered.countDown();
            };
            AlertDispatcher dispatcher =
                    new AlertDispatcher(enabled(), slow, new AlertThrottle(WINDOW), pool, clock);

            dispatcher.dispatch(alert("RK-05-breaker", RiskAlertEvent.Severity.CRITICAL, clock.nowMillis()));

            assertThat(mailStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(deliveries.get()).as("the call returned before the mail had been sent").isZero();

            release.countDown();
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(deliveries.get()).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aTransportThatThrowsDoesNotReachTheCallerAndTheNextAlertStillGoes() {
        RecordingTransport transport = new RecordingTransport();
        transport.failure = new IllegalStateException("SMTP refused");
        VirtualClock clock = new VirtualClock(T0);
        AlertDispatcher dispatcher =
                new AlertDispatcher(enabled(), transport, new AlertThrottle(WINDOW), Runnable::run, clock);

        // A failing transport is the degrade-to-log path: the caller must see nothing, and the engine
        // must keep dispatching. A second, different rule is what proves the second half - one alert
        // swallowed silently and one surviving would look identical if the dispatcher had died.
        assertThatCode(() -> dispatcher.dispatch(
                alert("RK-03-order", RiskAlertEvent.Severity.CRITICAL, clock.nowMillis())))
                .doesNotThrowAnyException();

        transport.failure = null;
        assertThatCode(() -> dispatcher.dispatch(
                alert("RK-04-portfolio", RiskAlertEvent.Severity.CRITICAL, clock.nowMillis())))
                .doesNotThrowAnyException();

        assertThat(transport.subjects).containsExactly("[alpha][CRITICAL] RK-04-portfolio");
    }

    @Test
    void anExecutorThatRefusesIsLoggedAndDoesNotStopTheLoop() {
        // A full queue or a shutting-down bean: there is no third option that both respects "never block
        // the loop" and preserves this alert, so it is dropped - loudly - and the loop continues.
        AlertDispatcher dispatcher = new AlertDispatcher(enabled(), new RecordingTransport(),
                new AlertThrottle(WINDOW), task -> {
                    throw new RejectedExecutionException("queue is full");
                }, new VirtualClock(T0));

        assertThatCode(() -> dispatcher.dispatch(
                alert("RK-06-frequency", RiskAlertEvent.Severity.WARNING, T0)))
                .doesNotThrowAnyException();
    }
}
