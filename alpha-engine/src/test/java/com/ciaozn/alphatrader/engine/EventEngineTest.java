package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.EventIds;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class EventEngineTest {

    private EventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void processesExternalEventsInFifoOrder() throws InterruptedException {
        engine = new EventEngine(EventJournal.noop(), new SystemClock());
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t) {
                seen.add(t.name());
                latch.countDown();
            }
        });
        engine.start();

        engine.publish(TimerEvent.of("first", 1));
        engine.publish(TimerEvent.of("second", 2));
        engine.publish(TimerEvent.of("third", 3));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly("first", "second", "third");
    }

    @Test
    void cascadeEventsCloseWithinSameRoundBeforeExternalOnes() throws InterruptedException {
        engine = new EventEngine(EventJournal.noop(), new SystemClock());
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        // Handler A: on "trigger", emits a cascade event
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t && t.name().equals("trigger")) {
                publisher.publish(TimerEvent.of("cascade", t.timestamp()));
            }
        });
        // Handler B: records everything
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t) {
                seen.add(t.name());
                latch.countDown();
            }
        });
        engine.start();

        engine.publish(TimerEvent.of("trigger", 1));
        engine.publish(TimerEvent.of("external", 2));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // cascade must be processed before the external event, even though "external"
        // was already sitting in the queue when "trigger" was dispatched
        assertThat(seen).containsExactly("trigger", "cascade", "external");
    }

    @Test
    void repeatingTimerFiresRepeatedly() throws InterruptedException {
        engine = new EventEngine(EventJournal.noop(), new SystemClock());
        CountDownLatch latch = new CountDownLatch(2);
        List<String> seen = new CopyOnWriteArrayList<>();
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t && t.name().equals("heartbeat")) {
                seen.add(t.name());
                latch.countDown();
            }
        });
        engine.start();
        engine.scheduleRepeating("heartbeat", Duration.ofMillis(50));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void timerScheduledWhileEngineParkedStillFires() throws InterruptedException {
        // Regression: engine parked on an empty queue must wake when a timer is scheduled.
        engine = new EventEngine(EventJournal.noop(), new SystemClock());
        CountDownLatch latch = new CountDownLatch(1);
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t && t.name().equals("oneShot")) {
                latch.countDown();
            }
        });
        engine.start();
        // no external events at all - the only way this fires is the wakeup path
        engine.scheduleOnce("oneShot", Duration.ofMillis(50));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void handlerExceptionDoesNotKillEngine() throws InterruptedException {
        engine = new EventEngine(EventJournal.noop(), new SystemClock());
        CountDownLatch latch = new CountDownLatch(1);
        engine.registerHandler((event, publisher) -> {
            throw new RuntimeException("buggy handler");
        });
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t && t.name().equals("alive")) {
                latch.countDown();
            }
        });
        engine.start();

        engine.publish(TimerEvent.of("boom", 1));
        engine.publish(TimerEvent.of("alive", 2));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.isRunning()).isTrue();
    }

    @Test
    void eventIdsAreMonotonic() {
        EventIds.reset();
        long a = EventIds.next();
        long b = EventIds.next();
        assertThat(b).isGreaterThan(a);
    }

    // ------------------------------------------------------------- T213 barrier

    private static final long T0 = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    @Test
    void awaitQuiescenceReturnsOnlyOnceTheEntireCascadeClosed() throws InterruptedException {
        VirtualClock clock = new VirtualClock(T0);
        engine = new EventEngine(EventJournal.noop(), clock);
        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch enteredSignal = new CountDownLatch(1);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t) {
                seen.add(t.name());
                switch (t.name()) {
                    case "bar" -> publisher.publish(TimerEvent.of("signal", t.timestamp()));
                    case "signal" -> {
                        publisher.publish(TimerEvent.of("order", t.timestamp()));
                        enteredSignal.countDown();
                        await(releaseSignal);
                    }
                    case "order" -> publisher.publish(TimerEvent.of("fill", t.timestamp()));
                    default -> {
                    }
                }
            }
        });
        engine.start();

        Event bar = TimerEvent.of("bar", T0);
        engine.publish(bar);
        assertThat(enteredSignal.await(5, TimeUnit.SECONDS)).isTrue();

        // "order" is queued but undispatched and the watermark is already past the bar's
        // id, so only "a round is open" can hold the barrier here.
        AtomicBoolean quiescent = new AtomicBoolean();
        CountDownLatch returned = new CountDownLatch(1);
        Thread driver = new Thread(() -> {
            try {
                quiescent.set(engine.awaitQuiescence(bar.eventId(), Duration.ofSeconds(5)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                returned.countDown();
            }
        }, "replay-driver");
        driver.start();

        assertThat(returned.await(200, TimeUnit.MILLISECONDS))
                .as("the cascade was still open")
                .isFalse();

        releaseSignal.countDown();
        assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
        driver.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(quiescent).isTrue();
        // the whole signal -> order -> fill chain, not just the bar that started it
        assertThat(seen).containsExactly("bar", "signal", "order", "fill");
        // an already-closed round must not make a second call block
        assertThat(engine.awaitQuiescence(bar.eventId(), Duration.ofMillis(200))).isTrue();
    }

    @Test
    void theClockStaysFrozenForAWholeRoundSoReplayIsReproducible() throws InterruptedException {
        VirtualClock clock = new VirtualClock(T0);
        engine = new EventEngine(EventJournal.noop(), clock);
        List<String> seen = new CopyOnWriteArrayList<>();
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t) {
                // record what the handler READS, not what the event carries: a driver that
                // advanced the clock mid-round would show up here as a chain with mixed times
                seen.add(t.name() + "@" + clock.nowMillis());
                if (t.name().equals("bar")) {
                    publisher.publish(TimerEvent.of("signal", t.timestamp()));
                } else if (t.name().equals("signal")) {
                    publisher.publish(TimerEvent.of("order", t.timestamp()));
                }
            }
        });
        engine.start();

        for (int index = 0; index < 3; index++) {
            long barTime = T0 + index * HOUR;
            clock.advanceTo(barTime);
            Event bar = TimerEvent.of("bar", barTime);
            engine.publish(bar);
            assertThat(engine.awaitQuiescence(bar.eventId())).isTrue();
        }

        assertThat(seen).containsExactly(
                "bar@" + T0, "signal@" + T0, "order@" + T0,
                "bar@" + (T0 + HOUR), "signal@" + (T0 + HOUR), "order@" + (T0 + HOUR),
                "bar@" + (T0 + 2 * HOUR), "signal@" + (T0 + 2 * HOUR), "order@" + (T0 + 2 * HOUR));
    }

    @Test
    void awaitQuiescenceHoldsUntilATimerThatBecameDueHasFired() throws InterruptedException {
        VirtualClock clock = new VirtualClock(T0);
        engine = new EventEngine(EventJournal.noop(), clock);
        List<String> seen = new CopyOnWriteArrayList<>();
        engine.registerHandler((event, publisher) -> {
            if (event instanceof TimerEvent t) {
                seen.add(t.name());
            }
        });
        engine.start();
        engine.scheduleRepeating("funding", Duration.ofHours(8));

        Event bar = TimerEvent.of("bar", T0);
        engine.publish(bar);
        assertThat(engine.awaitQuiescence(bar.eventId())).isTrue();
        assertThat(seen).containsExactly("bar");

        // The feeder jumps the clock, which is what makes funding due. The bar's round
        // closed long ago, so the only thing that may keep the barrier waiting is the
        // timer: returning here would let the feeder move on with funding unsettled.
        clock.advanceTo(T0 + Duration.ofHours(8).toMillis());
        assertThat(engine.awaitQuiescence(bar.eventId())).isTrue();

        assertThat(seen).containsExactly("bar", "funding");
    }

    @Test
    void awaitQuiescenceTimesOutForAnEventThatWasNeverPublished() throws InterruptedException {
        engine = new EventEngine(EventJournal.noop(), new VirtualClock(T0));
        engine.start();

        long started = System.nanoTime();
        assertThat(engine.awaitQuiescence(EventIds.next(), Duration.ofMillis(150))).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @Test
    void awaitQuiescenceGivesUpImmediatelyOnceTheEngineIsStopped() throws InterruptedException {
        engine = new EventEngine(EventJournal.noop(), new VirtualClock(T0));
        engine.start();
        engine.stop();

        long started = System.nanoTime();
        assertThat(engine.awaitQuiescence(EventIds.next(), Duration.ofSeconds(30))).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
