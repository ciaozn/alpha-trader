package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.execution.ClientOrderIds;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The single hard gate between signals and orders (FR-RK-01). P2 scope is the part the backtest
 * needs: position sizing (FR-RK-07) and the exchange minimum checks (spec edge case 4). The
 * account / order / portfolio / circuit-breaker / frequency rules of DESIGN §8 arrive in P3 as
 * an ordered pipeline in front of this step - nothing downstream changes when they do.
 *
 * <p>Every path out is an event: an {@link OrderRequestEvent} for the executor/OMS, or a
 * {@link RiskAlertEvent} (FR-RK-08). A signal that cannot be sized safely never becomes an
 * order; missing trading rules are treated as a hard stop, because without tickSize/stepSize/
 * minNotional there is no way to avoid sending a dirty one (FR-GW-03).
 *
 * <p>Runs on the event-engine thread only, so the counters need no synchronization. Orders go
 * out as MARKET: the backtest matcher fills at the next bar's open and the live OMS at the
 * touch, so a limit price here would be either look-ahead bias or a stale quote.
 */
public final class RiskGate implements EventHandler {

    public static final String RULE_MISSING_TRADING_RULES = "RK-07-no-trading-rules";

    private static final Logger log = LoggerFactory.getLogger(RiskGate.class);

    private final Portfolio portfolio;
    private final PositionSizer sizer;
    private final TradingRulesProvider tradingRules;
    private final Clock clock;
    private long sequence;
    private long ordersPassed;
    private long signalsBlocked;

    public RiskGate(Portfolio portfolio, PositionSizer sizer, TradingRulesProvider tradingRules, Clock clock) {
        this.portfolio = portfolio;
        this.sizer = sizer;
        this.tradingRules = tradingRules;
        this.clock = clock;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        if (event instanceof SignalEvent signal) {
            onSignal(signal, publisher);
        }
    }

    private void onSignal(SignalEvent signal, EventPublisher publisher) {
        Optional<TradingRules> rules = tradingRules.find(signal.symbol());
        if (rules.isEmpty()) {
            block(publisher, signal, RULE_MISSING_TRADING_RULES, RiskAlertEvent.Severity.CRITICAL,
                    "no trading rules cached for " + signal.symbol().unified() + ", cannot align precision");
            return;
        }

        PositionSizer.Result result = sizer.size(signal.direction(), signal.strength(), portfolio.equity(),
                portfolio.position(signal.symbol()).signedQty(), portfolio.markOf(signal.symbol()), rules.get());
        switch (result) {
            case PositionSizer.Result.NoTrade ignored -> log.debug("Signal {} {} {} needs no trade",
                    signal.strategyId(), signal.symbol().unified(), signal.direction());
            case PositionSizer.Result.Order order -> publishOrder(signal, order, publisher);
            case PositionSizer.Result.Rejected rejected ->
                    block(publisher, signal, rejected.ruleId(), rejected.severity(), rejected.detail());
        }
    }

    private void publishOrder(SignalEvent signal, PositionSizer.Result.Order order, EventPublisher publisher) {
        long now = clock.nowMillis();
        String clientOrderId = ClientOrderIds.of(signal.strategyId(), now, ++sequence);
        ordersPassed++;
        publisher.publish(OrderRequestEvent.of(clientOrderId, signal.symbol(), order.side(),
                OrderType.MARKET, order.qty(), null, now));
        log.info("Risk pass: {} {} {} strength={} -> MARKET {} qty={} [{}]",
                signal.strategyId(), signal.symbol().unified(), signal.direction(), signal.strength(),
                order.side(), order.qty().toPlainString(), clientOrderId);
    }

    private void block(EventPublisher publisher, SignalEvent signal, String ruleId,
                       RiskAlertEvent.Severity severity, String detail) {
        signalsBlocked++;
        String message = "signal " + signal.eventId() + " from " + signal.strategyId() + " "
                + signal.symbol().unified() + " " + signal.direction() + " blocked: " + detail;
        log.warn("Risk block [{}] {}", ruleId, message);
        publisher.publish(RiskAlertEvent.of(ruleId, severity, message, clock.nowMillis()));
    }

    /** Orders this gate let through. */
    public long ordersPassed() {
        return ordersPassed;
    }

    /** Signals this gate blocked (each one produced a RiskAlertEvent). */
    public long signalsBlocked() {
        return signalsBlocked;
    }
}
