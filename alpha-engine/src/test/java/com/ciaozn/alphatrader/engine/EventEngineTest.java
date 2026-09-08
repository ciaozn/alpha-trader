package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.EventIds;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.time.SystemClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
}
