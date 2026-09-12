package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.event.EventIds;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heartbeat answers "is anything happening", which is not the same question as "is the thread alive".
 *
 * <p>The last two tests are the ones that matter and they are deliberately about <em>time</em> rather than
 * about counting: the whole value of this component is that its answer goes stale when the stream dies, so
 * an implementation that returned a fixed instant would pass every counting test and report the same age
 * forever.
 */
class EngineHeartbeatTest {

    private static final long T0 = 1_700_000_000_000L;

    @AfterEach
    void tearDown() {
        EventIds.reset();
    }

    @Test
    void nothingObservedYetIsSaidAsAbsentRatherThanAsEpochZero() {
        EngineHeartbeat heartbeat = new EngineHeartbeat(new VirtualClock(T0));

        EngineHeartbeat.Reading reading = heartbeat.read(T0 + 5_000L);

        // Epoch 0 Is a real instant; reporting it would say "the last event was in 1970", which any
        // consumer taking the number at face value would report as a multi-decade outage.
        assertThat(reading.lastEventMillis()).isNull();
        assertThat(reading.sinceLastEventMillis()).isNull();
        assertThat(reading.eventsObserved()).isZero();
    }

    @Test
    void everyEventTypeCountsIncludingTimers() {
        VirtualClock clock = new VirtualClock(T0);
        EngineHeartbeat heartbeat = new EngineHeartbeat(clock);
        EventPublisher ignored = event -> { };

        heartbeat.onEvent(TimerEvent.of("heartbeat", T0), ignored);
        clock.advanceTo(T0 + 1_000L);
        heartbeat.onEvent(TimerEvent.of("snapshot", T0 + 1_000L), ignored);

        assertThat(heartbeat.read(T0 + 1_000L).eventsObserved()).isEqualTo(2);
        assertThat(heartbeat.read(T0 + 1_000L).lastEventMillis()).isEqualTo(T0 + 1_000L);
    }

    @Test
    void theAgeGrowsWhileNothingArrives() {
        VirtualClock clock = new VirtualClock(T0);
        EngineHeartbeat heartbeat = new EngineHeartbeat(clock);
        heartbeat.onEvent(TimerEvent.of("heartbeat", T0), ignored -> { });

        assertThat(heartbeat.read(T0 + 90_000L).sinceLastEventMillis()).isEqualTo(90_000L);
        assertThat(heartbeat.read(T0 + 91_000L).sinceLastEventMillis()).isEqualTo(91_000L);
    }

    @Test
    void businessTimeIsNotConfusedWithObservationTime() {
        // An event stamped with the exchange's time is not evidence about when the loop got to it: in a
        // halted process the timestamps stay old while nothing is being served at all, which is exactly
        // the failure this has to be able to see.
        VirtualClock clock = new VirtualClock(T0);
        EngineHeartbeat heartbeat = new EngineHeartbeat(clock);

        clock.advanceTo(T0 + 30_000L);
        heartbeat.onEvent(TimerEvent.of("late", T0 - 3_600_000L), ignored -> { });

        assertThat(heartbeat.read(T0 + 30_000L).lastEventMillis()).isEqualTo(T0 + 30_000L);
    }

    @Test
    void registeredOnARealEngineItSeesEveryEventThatRound() throws Exception {
        // The engine drains its task queue between rounds, so a heartbeat registered with the loop is
        // observing the same events the strategies are - offline here because nothing publishes except
        // this test.
        VirtualClock clock = new VirtualClock(T0);
        EventEngine engine = new EventEngine(EventJournal.noop(), clock);
        EngineHeartbeat heartbeat = new EngineHeartbeat(clock);
        engine.registerHandler(heartbeat);
        engine.start();
        try {
            TimerEvent second = TimerEvent.of("second", T0);
            engine.publish(TimerEvent.of("first", T0));
            engine.publish(second);

            assertThat(engine.awaitQuiescence(second.eventId(), Duration.ofSeconds(5))).isTrue();
            assertThat(heartbeat.read(T0).eventsObserved()).isEqualTo(2);
        } finally {
            engine.stop();
        }
    }
}
