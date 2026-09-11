package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.event.EventIds;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handler as the engine sees it (T401): what it answers to, and that it answers inside one round.
 *
 * <p>The last test runs through a real {@link EventEngine} instead of calling {@code onEvent} directly,
 * because the requirement is about the loop rather than about the object - "must not slow the loop down"
 * is only a statement where the loop is the caller. The journal is {@link EventJournal#noop()} and the
 * transport records into a list, so this stays offline and deterministic; the proof that the mail itself
 * leaves on another thread is {@link AlertDispatcherTest#theEngineThreadDoesNotWaitForTheMailToLeave},
 * which parks the send and watches the caller come back.
 */
class EmailAlertHandlerTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private AlertDispatcherTest.RecordingTransport transport;
    private EmailAlertHandler handler;

    @BeforeEach
    void setUp() {
        transport = new AlertDispatcherTest.RecordingTransport();
        AlertProperties properties = new AlertProperties(true, "to@example.com", "from@example.com",
                "user", WINDOW, "smtp.qq.com", 465, true);
        AlertDispatcher dispatcher = new AlertDispatcher(properties, transport,
                new AlertThrottle(WINDOW), Runnable::run, new VirtualClock(T0));
        handler = new EmailAlertHandler(dispatcher);
    }

    @AfterEach
    void tearDown() {
        EventIds.reset();
    }

    @Test
    void onlyRiskAlertsLeaveTheProcess() {
        // Every other event type is somebody else's business. Subscribing to a type rather than to
        // "whatever looks alarming" is what keeps this class from being edited every time a rule is.
        Symbol btc = Symbol.parse("BTCUSDT.PERP");
        BigDecimal one = BigDecimal.ONE;
        Kline bar = new Kline(T0, one, one, one, one, one, T0 + 1);
        handler.onEvent(KlineEvent.of(btc, Interval.H1, bar, true, T0), ignored -> { });

        assertThat(transport.subjects).isEmpty();
    }

    @Test
    void anAlertOnTheBusBecomesOneMail() {
        handler.onEvent(RiskAlertEvent.of("RK-05-breaker",
                RiskAlertEvent.Severity.CRITICAL, "trading paused", T0), ignored -> { });

        assertThat(transport.subjects).containsExactly("[alpha][CRITICAL] RK-05-breaker");
    }

    @Test
    void anAlertPublishedIntoARunningEngineArrivesInThatRound() throws Exception {
        EventEngine engine = new EventEngine(EventJournal.noop(), new VirtualClock(T0));
        engine.registerHandler(handler);
        engine.start();
        try {
            RiskAlertEvent alert = RiskAlertEvent.of("EX-reconcile-order-missing",
                    RiskAlertEvent.Severity.CRITICAL, "the exchange no longer lists this order", T0);
            engine.publish(alert);

            assertThat(engine.awaitQuiescence(alert.eventId(), Duration.ofSeconds(5)))
                    .as("the alert was dispatched without the round stalling on mail delivery")
                    .isTrue();
            assertThat(transport.subjects)
                    .containsExactly("[alpha][CRITICAL] EX-reconcile-order-missing");
        } finally {
            engine.stop();
        }
    }
}
