package com.ciaozn.alphatrader.backtest.match;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The simulated exchange (FR-BT-02, FR-BT-03). It plays the part Binance plays in live
 * trading, which is why matching, funding settlement and exchange-side refusals all happen
 * here and nowhere else: in live those three come from the exchange, so in backtest they must
 * come from the component standing in for it. Everything upstream - strategies, risk, the
 * event chain - is literally the same code in both modes (FR-BT-06).
 *
 * <p><b>The anti-look-ahead discipline.</b> A signal is produced by a closed bar and the order
 * waits. It fills at the OPEN of the next bar that arrives for that symbol, a price that did
 * not exist when the order was placed. Two details are easy to get wrong and are therefore
 * stated explicitly:
 * <ul>
 *   <li>slippage is estimated from the amplitude of the bar the order was <em>placed into</em>,
 *       never from the bar it fills on. The fill bar's high and low are future information at
 *       its own open, so using them to price the fill would leak exactly what the next-open
 *       rule exists to prevent;</li>
 *   <li>a bar that is still forming never trades. Its open is known but its close is not, and
 *       the feeder never emits one anyway.</li>
 * </ul>
 *
 * <p><b>Conservative bias.</b> Every cost is rounded against the trader: buys fill above the
 * open, sells below it, tick alignment goes the same way, and fees are charged on the slipped
 * price. A backtest that over-states costs is disappointing; one that under-states them is
 * expensive (DESIGN §10).
 *
 * <p><b>Business time.</b> A fill carries the business time of the round that produced it, the
 * same timestamp as the k-line event that triggered it, so every event of one round shares one
 * instant. The price is the next bar's open; the timestamp is not - the replay driver sets the
 * round's business time to the bar's close.
 *
 * <p>Runs on the event-engine thread only, so no state here is synchronized. All maps are
 * insertion-ordered and orders fill FIFO per symbol, so the same data produces the same fills
 * in the same order (NFR-04).
 */
public final class SimulatedExecutor implements EventHandler {

    public static final String RULE_UNSUPPORTED_ORDER_TYPE = "BT-unsupported-order-type";
    public static final String RULE_INVALID_QTY = "BT-invalid-qty";
    public static final String RULE_BELOW_MIN_NOTIONAL = "BT-fill-below-min-notional";
    public static final String RULE_NO_TRADING_RULES = "BT-fill-no-trading-rules";

    /** Binance settles perpetual funding every 8 hours on UTC-aligned boundaries. */
    public static final Duration FUNDING_INTERVAL = Duration.ofHours(8);

    private static final long FUNDING_INTERVAL_MILLIS = FUNDING_INTERVAL.toMillis();
    private static final BigDecimal BPS = new BigDecimal("10000");
    /** "No funding settled yet" - distinct from any real timestamp, including negative ones. */
    private static final long UNSET = Long.MIN_VALUE;
    private static final Logger log = LoggerFactory.getLogger(SimulatedExecutor.class);

    private final Portfolio portfolio;
    private final TradingRulesProvider tradingRules;
    private final CostModel cost;
    private final Map<Symbol, Deque<Pending>> pending = new LinkedHashMap<>();
    private final Map<Symbol, BigDecimal> slipOfLastBar = new LinkedHashMap<>();
    private final List<SimulatedFill> fills = new ArrayList<>();
    private final List<FundingSettlement> funding = new ArrayList<>();
    private final List<Rejection> rejections = new ArrayList<>();
    private long fundingSettledThrough = UNSET;
    private long ordersReceived;
    private long ordersFilled;

    /**
     * What the simulated exchange charges (DESIGN §10). Every default is pessimistic.
     *
     * <p>The bounds are typo guards rather than economics: a fee above 1%, a fixed slip above
     * 5% or a funding rate past Binance's ±0.75% cap is a mis-keyed configuration that would
     * otherwise silently rewrite every P&amp;L number in the report.
     */
    public record CostModel(BigDecimal takerFeeRate, BigDecimal fixedSlippageBps,
                            BigDecimal amplitudeFactor, BigDecimal fundingRatePerInterval) {

        /** taker 0.05%, 5bp fixed slip plus 5% of the bar's amplitude, 0.01% funding per 8h. */
        public static final CostModel DEFAULT = new CostModel(
                new BigDecimal("0.0005"), new BigDecimal("5"), new BigDecimal("0.05"),
                new BigDecimal("0.0001"));

        public CostModel {
            requireInRange("takerFeeRate", takerFeeRate, BigDecimal.ZERO, new BigDecimal("0.01"));
            requireInRange("fixedSlippageBps", fixedSlippageBps, BigDecimal.ZERO, new BigDecimal("500"));
            requireInRange("amplitudeFactor", amplitudeFactor, BigDecimal.ZERO, BigDecimal.ONE);
            requireInRange("fundingRatePerInterval", fundingRatePerInterval,
                    new BigDecimal("-0.0075"), new BigDecimal("0.0075"));
        }

        private static void requireInRange(String name, BigDecimal value, BigDecimal min, BigDecimal max) {
            if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException(name + " must be in [" + min.toPlainString() + ", "
                        + max.toPlainString() + "], got " + value);
            }
        }
    }

    /** One simulated execution, with the cost breakdown the report needs (FR-BT-04). */
    public record SimulatedFill(String clientOrderId, Symbol symbol, Side side, long fillBarOpenTime,
                                BigDecimal barOpen, BigDecimal slippageBps, BigDecimal fillPrice,
                                BigDecimal qty, BigDecimal fee, BigDecimal realizedPnl, long businessTs) {
    }

    /** One funding settlement on one position (spec edge case 5). */
    public record FundingSettlement(Symbol symbol, long boundaryMillis, BigDecimal fundingRate,
                                    BigDecimal signedQty, BigDecimal markPrice, BigDecimal charge) {
    }

    /** An order the simulated exchange refused, for the reason a real one would have. */
    public record Rejection(String clientOrderId, Symbol symbol, String reason, long businessTs) {
    }

    private record Pending(OrderRequestEvent order, BigDecimal slippageBps) {
    }

    public SimulatedExecutor(Portfolio portfolio, TradingRulesProvider tradingRules, CostModel cost) {
        this.portfolio = portfolio;
        this.tradingRules = tradingRules;
        this.cost = cost;
    }

    public SimulatedExecutor(Portfolio portfolio, TradingRulesProvider tradingRules) {
        this(portfolio, tradingRules, CostModel.DEFAULT);
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        switch (event) {
            case KlineEvent kline -> onKline(kline, publisher);
            case OrderRequestEvent order -> onOrderRequest(order, publisher);
            // Signals, fills, order updates and alerts are not the exchange's business.
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ the exchange side

    private void onKline(KlineEvent event, EventPublisher publisher) {
        if (!event.closed()) {
            return;
        }
        Kline bar = event.kline();
        fillPending(event.symbol(), bar, event.timestamp(), publisher);
        portfolio.mark(event.symbol(), bar.close());
        // Recorded before this handler returns, and that is what makes it this bar's amplitude:
        // publishing enqueues rather than calls, so every order placed in this round - a strategy's
        // signal, or one emitted from onFill - is placed after onKline has finished and reads this
        // bar. Its position relative to fillPending above is not load-bearing: a fill takes the
        // slippage stored in its own Pending and never reads this map.
        slipOfLastBar.put(event.symbol(), slippageBps(bar));
        settleFunding(bar.closeTime());
    }

    private void fillPending(Symbol symbol, Kline bar, long businessTs, EventPublisher publisher) {
        Deque<Pending> queue = pending.get(symbol);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        Optional<TradingRules> rules = tradingRules.find(symbol);
        if (rules.isEmpty()) {
            // The risk gate refuses to size an order without rules, so getting here means the
            // provider lost them mid-run. Filling anyway would mean inventing a tick size.
            while (!queue.isEmpty()) {
                reject(queue.removeFirst().order(), businessTs, RULE_NO_TRADING_RULES,
                        RiskAlertEvent.Severity.CRITICAL,
                        "no trading rules for " + symbol.unified() + " at fill time, cannot align to tickSize",
                        publisher);
            }
            return;
        }
        // FIFO at one price: a real exchange would walk the book, and a single open price gives
        // nothing to walk, so every order queued for this bar takes the same slipped open.
        while (!queue.isEmpty()) {
            fill(queue.removeFirst(), symbol, bar, rules.get(), businessTs, publisher);
        }
    }

    private void fill(Pending pending, Symbol symbol, Kline bar, TradingRules rules,
                      long businessTs, EventPublisher publisher) {
        OrderRequestEvent order = pending.order();
        BigDecimal slippage = pending.slippageBps();
        BigDecimal fillPrice = fillPrice(order.side(), bar.open(), slippage, rules);
        BigDecimal qty = order.qty();
        if (!rules.meetsMinNotional(qty, fillPrice)) {
            // Spec edge case 4: skip and alert, never send an order the exchange would refuse.
            // The gate checked against the mark price; the slipped fill price can still dip under.
            reject(order, businessTs, RULE_BELOW_MIN_NOTIONAL, RiskAlertEvent.Severity.WARNING,
                    "qty " + qty.toPlainString() + " at fill price " + fillPrice.toPlainString()
                            + " is below minNotional " + rules.minNotional().toPlainString(), publisher);
            return;
        }
        BigDecimal fee = Money.of(fillPrice.multiply(qty, Money.MC)
                .multiply(cost.takerFeeRate(), Money.MC));
        // Applied before publishing, so anything reacting to the fill - a strategy's onFill,
        // the report - sees the position this fill actually produced.
        Portfolio.FillResult result = portfolio.applyFill(symbol, order.side(), fillPrice, qty, fee);
        ordersFilled++;
        fills.add(new SimulatedFill(order.clientOrderId(), symbol, order.side(), bar.openTime(),
                bar.open(), slippage, fillPrice, qty, fee, result.realizedPnl(), businessTs));
        publisher.publish(FillEvent.of(order.clientOrderId(), symbol, order.side(),
                fillPrice, qty, fee, businessTs));
        log.debug("Simulated fill {} {} {} qty={} at {} (bar {} open {}, slip {}bp, fee {})",
                order.clientOrderId(), symbol.unified(), order.side(), qty.toPlainString(),
                fillPrice.toPlainString(), bar.openTime(), bar.open().toPlainString(),
                slippage.toPlainString(), fee.toPlainString());
    }

    /**
     * The slipped open, aligned to tickSize. Both steps go against the trader: a buy pays up
     * (CEILING), a sell receives less (FLOOR). Note this is the opposite of the limit-order
     * convention in {@link TradingRules#alignPrice} - there the question is what to ask for,
     * here it is what a taker actually pays.
     */
    private BigDecimal fillPrice(Side side, BigDecimal barOpen, BigDecimal slippageBps, TradingRules rules) {
        BigDecimal slipFactor = Money.divide(slippageBps, BPS);
        BigDecimal slipped = side == Side.BUY
                ? barOpen.multiply(BigDecimal.ONE.add(slipFactor), Money.MC)
                : barOpen.multiply(BigDecimal.ONE.subtract(slipFactor), Money.MC);
        return rules.alignPrice(slipped, side == Side.BUY ? RoundingMode.CEILING : RoundingMode.FLOOR);
    }

    /** Fixed basis points plus a share of the bar's amplitude, in basis points. */
    private BigDecimal slippageBps(Kline bar) {
        BigDecimal amplitudeBps = Money.divide(bar.high().subtract(bar.low()), bar.open())
                .multiply(BPS, Money.MC);
        return cost.fixedSlippageBps()
                .add(amplitudeBps.multiply(cost.amplitudeFactor(), Money.MC), Money.MC);
    }

    /**
     * Settles every funding boundary the bar just passed, on the positions open at that moment.
     * Driven by the data rather than by a scheduled timer so that the boundaries land on the
     * exchange's own UTC marks regardless of when the run started, and so that a hole in the
     * data still settles the boundaries the exchange really did settle (spec edge cases 3 and 5).
     * A boundary that fell inside such a hole is settled at the last observed close, because
     * its own price is precisely what is missing.
     */
    private void settleFunding(long closeTime) {
        if (fundingSettledThrough == UNSET) {
            // Nothing can be owed before the first bar: the book is flat until a fill and a fill
            // cannot precede the first bar. Anchoring here also stops the first bar from trying
            // to settle every boundary since the epoch.
            fundingSettledThrough = closeTime;
            return;
        }
        long boundary = firstBoundaryAfter(fundingSettledThrough);
        while (boundary <= closeTime) {
            for (Position position : portfolio.openPositions()) {
                BigDecimal mark = portfolio.markOf(position.symbol());
                BigDecimal charge = portfolio.applyFunding(position.symbol(), cost.fundingRatePerInterval());
                if (charge.signum() == 0) {
                    continue;
                }
                funding.add(new FundingSettlement(position.symbol(), boundary, cost.fundingRatePerInterval(),
                        position.signedQty(), mark, charge));
            }
            fundingSettledThrough = boundary;
            boundary += FUNDING_INTERVAL_MILLIS;
        }
    }

    /** First UTC-aligned funding boundary strictly after {@code millis}. */
    private static long firstBoundaryAfter(long millis) {
        return Math.floorDiv(millis, FUNDING_INTERVAL_MILLIS) * FUNDING_INTERVAL_MILLIS + FUNDING_INTERVAL_MILLIS;
    }

    // ------------------------------------------------------------------ the order side

    private void onOrderRequest(OrderRequestEvent order, EventPublisher publisher) {
        ordersReceived++;
        if (order.orderType() != OrderType.MARKET) {
            reject(order, order.timestamp(), RULE_UNSUPPORTED_ORDER_TYPE, RiskAlertEvent.Severity.WARNING,
                    "the simulated exchange only matches MARKET, got " + order.orderType()
                            + " - limit fills would need a book model", publisher);
            return;
        }
        if (order.qty() == null || order.qty().signum() <= 0) {
            reject(order, order.timestamp(), RULE_INVALID_QTY, RiskAlertEvent.Severity.WARNING,
                    "qty must be > 0, got " + order.qty(), publisher);
            return;
        }
        // Slippage comes from the last closed bar of this symbol, the one the order was placed
        // into. The bar it fills on has not happened yet at this point, which is the whole idea.
        BigDecimal slippage = slipOfLastBar.getOrDefault(order.symbol(), cost.fixedSlippageBps());
        pending.computeIfAbsent(order.symbol(), symbol -> new ArrayDeque<>())
                .addLast(new Pending(order, slippage));
        log.debug("Order {} {} {} qty={} queued for the next bar (slip {}bp)", order.clientOrderId(),
                order.symbol().unified(), order.side(), order.qty().toPlainString(), slippage.toPlainString());
    }

    private void reject(OrderRequestEvent order, long businessTs, String ruleId,
                        RiskAlertEvent.Severity severity, String detail, EventPublisher publisher) {
        String message = "order " + order.clientOrderId() + " " + order.symbol().unified() + " "
                + order.side() + " refused: " + detail;
        rejections.add(new Rejection(order.clientOrderId(), order.symbol(), detail, businessTs));
        log.warn("Simulated exchange [{}] {}", ruleId, message);
        publisher.publish(RiskAlertEvent.of(ruleId, severity, message, businessTs));
    }

    // ------------------------------------------------------------------ views

    /** Every simulated execution, in fill order. */
    public List<SimulatedFill> fills() {
        return Collections.unmodifiableList(fills);
    }

    /** Every funding settlement, in boundary order. */
    public List<FundingSettlement> funding() {
        return Collections.unmodifiableList(funding);
    }

    /** Every order the simulated exchange refused. */
    public List<Rejection> rejections() {
        return Collections.unmodifiableList(rejections);
    }

    /**
     * Orders still waiting for a bar. Non-empty at the end of a replay means the last signal of
     * the run never got its fill - worth reporting rather than quietly forgetting.
     */
    public List<OrderRequestEvent> pendingOrders() {
        List<OrderRequestEvent> waiting = new ArrayList<>();
        for (Deque<Pending> queue : pending.values()) {
            for (Pending item : queue) {
                waiting.add(item.order());
            }
        }
        return Collections.unmodifiableList(waiting);
    }

    public long ordersReceived() {
        return ordersReceived;
    }

    public long ordersFilled() {
        return ordersFilled;
    }

    public long ordersRejected() {
        return rejections.size();
    }

    public CostModel costModel() {
        return cost;
    }
}
