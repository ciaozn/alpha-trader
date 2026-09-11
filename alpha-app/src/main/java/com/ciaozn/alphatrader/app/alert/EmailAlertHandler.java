package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The bus-side half of alerting (T401, FR-OP-03): turns {@link RiskAlertEvent}s into mail, doing no
 * IO of its own.
 *
 * <p><b>Why {@code RiskAlertEvent} is the only type subscribed.</b> FR-OP-03 names five things that
 * must reach the mailbox - 成交 / 风控拦截 / 熔断 / 断线重连 / 对账异常 - and all five are published on
 * one channel: FR-RK-08's alert event. Filtering by type instead of by reading each event's fields is
 * what keeps this handler from becoming the place where "what counts as an alert" is decided anew;
 * anything that can happen twice should be able to appear here without touching this class.
 *
 * <p><b>Registered last, deliberately.</b> It is appended after {@code OnlineHandlers}' sequence (see
 * {@link AlertWiring}) so it observes the alert on the same round it was raised and nothing it does can
 * be read back by rule that produced it. It publishes nothing: an alert about alerting would be an
 * email about emails, and the failure path is already the log.
 *
 * <p><b>This handler must not throw.</b> {@code EventEngine.dispatch} catches per handler, so a throw
 * here would not kill the loop, but it would also not be visible anywhere except one error line; the
 * dispatcher already guarantees nothing escapes, and this class keeps that promise it inherits.
 *
 * <p>Online modes only: neither backtest nor download has anywhere to send an alert - a replay that
 * crossed the same breaker 400 times would otherwise have written to somebody's mailbox 400 times.
 */
@Component
@Profile({"paper", "live"})
public final class EmailAlertHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertHandler.class);

    private final AlertDispatcher dispatcher;

    public EmailAlertHandler(AlertDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        if (event instanceof RiskAlertEvent alert) {
            dispatcher.dispatch(alert);
        }
    }

    /** Said once at registration so the startup log records whether anybody would be told anything. */
    void logArmed(AlertProperties properties) {
        log.info("Email alert handler registered (enabled={}, window={})",
                properties.active(), properties.aggregationWindow());
    }
}
