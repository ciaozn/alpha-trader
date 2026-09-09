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
 * The single hard gate between signals and orders (FR-RK-01). Nothing else in the system turns a
 * {@link SignalEvent} into an {@link OrderRequestEvent}, so every rule that must hold before money
 * moves is enforced here or nowhere.
 *
 * <p>One signal walks four steps, and each can end the attempt:
 * <ol>
 *   <li>cached trading rules must exist - without tickSize/stepSize/minNotional there is no way to
 *       avoid sending a dirty order (FR-GW-03), so this is a hard stop rather than a rejection;</li>
 *   <li>the pipeline's {@link SignalRule}s (account, circuit breaker, frequency) see the signal and
 *       the account, before any quantity exists;</li>
 *   <li>FR-RK-07 sizes the signal, and rejects anything that rounds below the exchange minimum
 *       (spec edge case 4);</li>
 *   <li>the pipeline's {@link OrderRule}s (order, portfolio) see the concrete order and the book as
 *       it would be if that order filled.</li>
 * </ol>
 * Steps 2 and 4 are two stages rather than one list because step 3 is what produces the quantity the
 * late rules need - see {@link RiskRule}.
 *
 * <p>Every path out is an event: an {@link OrderRequestEvent} for the executor/OMS, or a
 * {@link RiskAlertEvent} (FR-RK-08). One {@link SignalFacts} snapshot is taken before step 2 and
 * reused by every later step, so the rules, the sizer and the interception record all describe the
 * same instant - re-reading the book per step would let a mark arriving mid-decision make the alert
 * disagree with the decision it explains.
 *
 * <p>Runs on the event-engine thread only, so the counters need no synchronization. Orders go out as
 * MARKET: the backtest matcher fills at the next bar's open and the live OMS at the touch, so a limit
 * price here would be either look-ahead bias or a stale quote.
 */
public final class RiskGate implements EventHandler {

    public static final String RULE_MISSING_TRADING_RULES = "RK-07-no-trading-rules";

    private static final Logger log = LoggerFactory.getLogger(RiskGate.class);

    private final Portfolio portfolio;
    private final PositionSizer sizer;
    private final TradingRulesProvider tradingRules;
    private final RiskPipeline pipeline;
    private final Clock clock;
    private long sequence;
    private long ordersPassed;
    private long signalsBlocked;

    public RiskGate(Portfolio portfolio, PositionSizer sizer, TradingRulesProvider tradingRules,
                    RiskPipeline pipeline, Clock clock) {
        this.portfolio = portfolio;
        this.sizer = sizer;
        this.tradingRules = tradingRules;
        this.pipeline = pipeline;
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
            block(publisher, signal, new RiskRejection(RULE_MISSING_TRADING_RULES, RiskRule.Level.SIZING,
                    RiskAlertEvent.Severity.CRITICAL,
                    "no trading rules cached for " + signal.symbol().unified() + ", cannot align precision"));
            return;
        }

        SignalFacts facts = SignalFacts.of(signal, portfolio, clock.nowMillis());
        Optional<RiskRejection> beforeSizing = pipeline.checkSignal(facts);
        if (beforeSizing.isPresent()) {
            block(publisher, signal, beforeSizing.get());
            return;
        }

        PositionSizer.Result result = sizer.size(signal.direction(), signal.strength(), facts.equity(),
                facts.signedQty(), facts.price(), rules.get());
        switch (result) {
            case PositionSizer.Result.NoTrade ignored -> log.debug("Signal {} {} {} needs no trade",
                    signal.strategyId(), signal.symbol().unified(), signal.direction());
            case PositionSizer.Result.Order order -> onOrder(signal, facts, order, publisher);
            case PositionSizer.Result.Rejected rejected -> block(publisher, signal, new RiskRejection(
                    rejected.ruleId(), RiskRule.Level.SIZING, rejected.severity(), rejected.detail()));
        }
    }

    private void onOrder(SignalEvent signal, SignalFacts facts, PositionSizer.Result.Order order,
                         EventPublisher publisher) {
        Optional<RiskRejection> afterSizing = pipeline.checkOrder(OrderFacts.of(facts, order.side(), order.qty()));
        if (afterSizing.isPresent()) {
            block(publisher, signal, afterSizing.get());
            return;
        }
        publishOrder(signal, order, publisher);
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

    private void block(EventPublisher publisher, SignalEvent signal, RiskRejection rejection) {
        signalsBlocked++;
        String message = "signal " + signal.eventId() + " from " + signal.strategyId() + " "
                + signal.symbol().unified() + " " + signal.direction() + " blocked: " + rejection.detail();
        log.warn("Risk block [{}] {}", rejection.ruleId(), message);
        publisher.publish(RiskAlertEvent.of(rejection.ruleId(), rejection.severity(), message, clock.nowMillis()));
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
