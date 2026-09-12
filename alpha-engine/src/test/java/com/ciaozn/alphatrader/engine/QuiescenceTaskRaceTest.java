package com.ciaozn.alphatrader.engine;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quiescence barrier counts work handed in from other threads as work (T-engine).
 *
 * <p><b>What went wrong without that.</b> A caller could submit a {@code runOnLoop} task, publish a
 * probe, wait for quiescence, and then find that what the task was supposed to produce had never
 * happened: the barrier reported "nothing left to do" while the task was still queued. It surfaced as
 * an intermittent failure of an unrelated test, which is how races usually announce themselves.
 *
 * <p><b>What this test can and cannot pin.</b> It pins the contract the barrier can actually make: a
 * task that has <em>run</em> has also been flushed, and quiescence implies the log contains everything
 * published up to that point. It deliberately does not assert anything about a task that is still
 * queued when the probe is published - delivery of loop tasks is a separate queue woken by an
 * interrupt, so "submitted before the probe" does not by itself order the two. Callers that need the
 * task to be finished before they read its output wait for it (see the latch), which is what the
 * reload path now does.
 */
class QuiescenceTaskRaceTest {

    private EventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void workHandedInFromAnotherThreadIsCountedAsWorkUntilItHasRun() throws Exception {
        VirtualClock clock = new VirtualClock(1_700_000_000_000L);
        engine = new EventEngine(EventJournal.noop(), clock);
        List<Long> seen = new CopyOnWriteArrayList<>();
        engine.registerHandler((Event event, EventPublisher publisher) -> seen.add(event.eventId()));
        engine.start();

        for (int round = 0; round < 200; round++) {
            TimerEvent fromTask = TimerEvent.of("from-task", clock.nowMillis());
            CountDownLatch ran = new CountDownLatch(1);
            engine.runOnLoop(() -> {
                engine.publish(fromTask);
                ran.countDown();
            });
            // The task is done before we ask the barrier anything; from here the bar has been
            // published, so quiescence must include it.
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();

            if (round % 2 == 0) {
                // Every other round asks the barrier via a later probe: the event published by the
                // task is already queued, so the barrier cannot call the engine idle before it ran.
                TimerEvent probe = TimerEvent.of("probe", clock.nowMillis());
                engine.publish(probe);
                assertThat(engine.awaitQuiescence(probe.eventId())).isTrue();
            } else {
                assertThat(engine.awaitQuiescence(fromTask.eventId())).isTrue();
            }
            assertThat(seen).as("round %d", round).contains(fromTask.eventId());
        }
    }
}
