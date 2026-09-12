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

import java.util.List;
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
 * {@link RiskAlertEvent} (FR-RK-08). <b>A blocked signal is also a row.</b> The gate writes an
 * {@link InterceptionRecord} before it publishes the alert, for the reason every write-then-announce pair
 * in this system has: FR-RK-08 requires the refusal to be queryable with the rule that refused and the
 * account it refused against, and the one consumer that can be relied on to go and look is a consumer
 * reacting to the alert. It writes the row itself rather than leaving it to an observer on the bus
 * because {@link SignalFacts} never reaches the bus - it is taken inside one {@code onEvent} call - so no
 * handler registration order could recover the half of the record that makes it worth keeping.
 *
 * <p>One {@link SignalFacts} snapshot is taken before step 1 and reused by every later step, by the alert
 * and by the record, so all of them describe the same instant - re-reading the book per step would let a
 * mark arriving mid-decision make the alert disagree with the decision it explains. Taking it before step
 * 1 rather than step 2 is what lets the hard stop record anything at all: it refuses for want of cached
 * precision, which says nothing about the account, and a refusal with no account behind it cannot answer
 * "was that right?".
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
    /**
     * The rule set, replaceable at runtime (T404, FR-RK-09). Volatile for the reason {@link #reload}
     * documents: the swap is written from the reload thread and read from the loop.
     */
    private volatile RiskPipeline pipeline;
    private final Clock clock;
    private final RecordStore records;
    private long sequence;
    private long ordersPassed;
    private long signalsBlocked;

    /**
     * @param records where refusals are written. Required in all three modes rather than optional: a
     *                nullable store would give the block path two shapes and only one of them would be
     *                tested. A mode that runs without a business database is given
     *                {@link InMemoryRecordStore}, which is what that class exists for.
     */
    public RiskGate(Portfolio portfolio, PositionSizer sizer, TradingRulesProvider tradingRules,
                    RiskPipeline pipeline, Clock clock, RecordStore records) {
        this.portfolio = portfolio;
        this.sizer = sizer;
        this.tradingRules = tradingRules;
        this.pipeline = pipeline;
        this.clock = clock;
        this.records = records;
    }

    /**
     * Replaces the rule set at runtime (T404, FR-RK-09 / SEC-03). The parameter values come from
     * configuration; how they become rules is {@code RiskPipelines}' job, and validation happens where
     * the new pipeline is built - an invalid configuration never reaches this method, so a failed
     * reload leaves the previous rules in force rather than applying part of an edit.
     *
     * <p><b>Atomic, and only for signals that arrive afterwards.</b> The field is volatile, so the
     * reference a signal reads is always one whole pipeline - old or new, never a half-swapped mix.
     * A signal already being decided keeps the pipeline it read; the swap takes effect at the next
     * {@link SignalEvent}. Swapping on the loop thread (as the reload endpoint does) makes that exact
     * rather than merely safe: no signal can be mid-decision while the reference changes.
     *
     * <p><b>What it does not swap.</b> Rules that are also {@code EventHandler}s - the circuit breaker
     * - were registered on the bus once at startup. This method changes what the gate consults; it does
     * not change what the bus dispatches fills to. So after reloading a pipeline built with a fresh
     * breaker, the gate's daily-loss check still reads the current account (it is re-observed on every
     * signal), but the losing-streak counter lives on the old instance and can no longer advance. A
     * deployment that needs to retune the breaker's stateful trigger should restart; the alternative -
     * re-registering handlers under a running loop - would let a reload reorder dispatch, which is a
     * worse failure than a restart.
     */
    public void reload(RiskPipeline replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("replacement pipeline must not be null: a null would"
                    + " leave the gate with no rules at all, which is not a reload");
        }
        this.pipeline = replacement;
        log.info("Risk pipeline reloaded on the engine thread: {} signal rule(s) [{}], {} order rule(s) [{}]",
                replacement.signalRules().size(), ids(replacement.signalRules()),
                replacement.orderRules().size(), ids(replacement.orderRules()));
    }

    /** The pipeline currently in force. Read for the audit record and by tests. */
    public RiskPipeline pipeline() {
        return pipeline;
    }

    private static String ids(List<? extends RiskRule> rules) {
        return rules.stream().map(RiskRule::ruleId).reduce((a, b) -> a + ", " + b).orElse("");
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        if (event instanceof SignalEvent signal) {
            onSignal(signal, publisher);
        }
    }

    private void onSignal(SignalEvent signal, EventPublisher publisher) {
        // First, before anything is checked: it needs no trading rules, both book reads are pure, and
        // taking it here is what lets the hard stop below record the account it refused against.
        SignalFacts facts = SignalFacts.of(signal, portfolio, clock.nowMillis());

        Optional<TradingRules> rules = tradingRules.find(signal.symbol());
        if (rules.isEmpty()) {
            block(publisher, facts, new RiskRejection(RULE_MISSING_TRADING_RULES, RiskRule.Level.SIZING,
                    RiskAlertEvent.Severity.CRITICAL,
                    "no trading rules cached for " + signal.symbol().unified() + ", cannot align precision"));
            return;
        }

        Optional<RiskRejection> beforeSizing = pipeline.checkSignal(facts);
        if (beforeSizing.isPresent()) {
            block(publisher, facts, beforeSizing.get());
            return;
        }

        PositionSizer.Result result = sizer.size(signal.direction(), signal.strength(), facts.equity(),
                facts.signedQty(), facts.price(), rules.get());
        switch (result) {
            case PositionSizer.Result.NoTrade ignored -> log.debug("Signal {} {} {} needs no trade",
                    signal.strategyId(), signal.symbol().unified(), signal.direction());
            case PositionSizer.Result.Order order -> onOrder(facts, order, publisher);
            case PositionSizer.Result.Rejected rejected -> block(publisher, facts, new RiskRejection(
                    rejected.ruleId(), RiskRule.Level.SIZING, rejected.severity(), rejected.detail()));
        }
    }

    private void onOrder(SignalFacts facts, PositionSizer.Result.Order order, EventPublisher publisher) {
        Optional<RiskRejection> afterSizing = pipeline.checkOrder(OrderFacts.of(facts, order.side(), order.qty()));
        if (afterSizing.isPresent()) {
            block(publisher, facts, afterSizing.get());
            return;
        }
        publishOrder(facts.signal(), order, publisher);
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

    /**
     * The one way out that is not an order: one row, then one alert.
     *
     * <p>The row goes first for the reason every write-then-announce pair in this system has - a consumer
     * that reacts to the alert by querying FR-RK-08's record has to find it already there. The alert is
     * stamped with the snapshot's instant rather than with a fresh clock read, because in live the clock
     * is the wall clock and time passes while the pipeline runs: a second read here is what would let the
     * alert and the record explaining it carry different timestamps, and {@link InterceptionRecord}'s
     * contract is that its timestamp is the one the snapshot was taken with. One decision, one instant.
     *
     * <p>The signal comes out of the facts rather than arriving beside them, so the row and the alert
     * cannot be built from two different signals even by a caller that has both in scope.
     */
    private void block(EventPublisher publisher, SignalFacts facts, RiskRejection rejection) {
        signalsBlocked++;
        SignalEvent signal = facts.signal();
        String message = "signal " + signal.eventId() + " from " + signal.strategyId() + " "
                + signal.symbol().unified() + " " + signal.direction() + " blocked: " + rejection.detail();
        log.warn("Risk block [{}] {}", rejection.ruleId(), message);
        records.saveInterception(new InterceptionRecord(facts, rejection));
        publisher.publish(RiskAlertEvent.of(rejection.ruleId(), rejection.severity(), message, facts.nowMillis()));
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
