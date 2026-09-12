package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.execution.ClientOrderIds;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The comparison at the heart of reconciliation (T318, FR-EX-04): local book and order rows against
 * what the exchange says, producing the corrections and alerts to publish.
 *
 * <p><b>Pure with respect to the network.</b> {@link #reconcile} takes a snapshot the caller already
 * gathered, so the three divergence cases are testable without an exchange: a class that queried the
 * gateway inside its comparison could only be tested by becoming an exchange.
 *
 * <p><b>The exchange wins, always.</b> When local and remote disagree the local state is rewritten to
 * match, because "we think we are flat" is not an argument the market accepts. Every rewrite is
 * announced: a correction nobody is told about is worse than the bug it fixed, since the numbers now
 * look authoritative for the wrong reason.
 *
 * <p>What it does about a ghost position depends on configuration (T405, FR-EX-05). A ghost - one the
 * exchange holds and this process has no record of - is always reported CRITICAL. It is additionally
 * closed only when {@code autoCloseGhostPositions} was configured on: then this class also returns a
 * MARKET {@link OrderRequestEvent} that nets the position to zero, and stops there. <b>It never calls a
 * gateway</b> - the order goes back to the caller, is published, and reaches the OMS over the normal
 * path, so it gets a row, an outbound send and a fill like any other order. A reconciliation pass that
 * placed the order itself would leave an execution the next pass could not see. The default is alert
 * only, because a position we did not open may be one we do not understand.
 *
 * <p>An order we believe is open that the exchange no longer lists is cancelled here, and that is a
 * judgement worth stating: a resting order disappears either by being filled or by being cancelled,
 * and a fill always comes with a trade report. If the report is missing, cancelling is still the
 * better error - the alternative is a book that keeps waiting for an order that no longer exists,
 * while the position check in the same pass reports the exposure that fill created.
 */
public final class Reconciler {

    public static final String RULE_MISSING_ORDER = "EX-reconcile-order-missing";
    public static final String RULE_UNKNOWN_ORDER = "EX-reconcile-order-unknown";
    public static final String RULE_POSITION_MISMATCH = "EX-reconcile-position-mismatch";
    public static final String RULE_GHOST_POSITION = "EX-reconcile-ghost-position";
    public static final String RULE_EQUITY_MISMATCH = "EX-reconcile-equity-mismatch";

    /**
     * Prefix of the clientOrderId an auto-close carries. The rest is the symbol, which makes the id
     * stable across passes on purpose: a ghost the exchange still holds after the close was sent gets
     * the same id again, and the OMS's own idempotency (FR-EX-02) drops the redelivery instead of
     * stacking a second close order on top of the first.
     */
    public static final String GHOST_CLOSE_ID_PREFIX = "ghost-close-";

    /** Default tolerance for "our equity and the exchange's disagree": 1%. */
    public static final BigDecimal DEFAULT_EQUITY_TOLERANCE = new BigDecimal("0.01");

    /** What the exchange reports, gathered once per pass by {@link ReconciliationRunner}. */
    public record ExchangeState(List<OpenOrder> openOrders, List<Position> positions, AccountSnapshot account) {
    }

    private final OrderStore orders;
    private final Portfolio portfolio;
    private final Clock clock;
    private final BigDecimal equityTolerance;
    private final boolean autoCloseGhostPositions;

    public Reconciler(OrderStore orders, Portfolio portfolio, Clock clock) {
        this(orders, portfolio, clock, DEFAULT_EQUITY_TOLERANCE, false);
    }

    public Reconciler(OrderStore orders, Portfolio portfolio, Clock clock, BigDecimal equityTolerance) {
        this(orders, portfolio, clock, equityTolerance, false);
    }

    /**
     * @param autoCloseGhostPositions T405 / FR-EX-05: when true a ghost position also produces the
     *        MARKET order that flattens it, returned alongside the alert. Off preserves P3's
     *        alert-only behaviour, which is the default everywhere the flag is not configured.
     */
    public Reconciler(OrderStore orders, Portfolio portfolio, Clock clock, BigDecimal equityTolerance,
                      boolean autoCloseGhostPositions) {
        this.orders = orders;
        this.portfolio = portfolio;
        this.clock = clock;
        this.equityTolerance = equityTolerance;
        this.autoCloseGhostPositions = autoCloseGhostPositions;
    }

    /**
     * Compares the two sides, applies position corrections to the book, and returns the events the
     * caller must publish: order reports for orders that vanished, alerts for everything it changed
     * or could not explain.
     */
    public List<Event> reconcile(ExchangeState exchange) {
        long now = clock.nowMillis();
        List<Event> events = new ArrayList<>();
        events.addAll(reconcileOrders(exchange.openOrders(), now));
        events.addAll(reconcilePositions(exchange.positions(), now));
        events.addAll(reconcileEquity(exchange.account(), now));
        return List.copyOf(events);
    }

    private List<Event> reconcileOrders(List<OpenOrder> remote, long now) {
        Map<String, OpenOrder> byId = new LinkedHashMap<>();
        for (OpenOrder order : remote) {
            byId.put(order.clientOrderId(), order);
        }
        List<Event> events = new ArrayList<>();
        for (OrderRecord local : orders.findOpen()) {
            if (byId.containsKey(local.clientOrderId())) {
                continue;
            }
            events.add(OrderReportEvent.of(local.clientOrderId(), local.exchangeOrderId(),
                    OrderStatus.CANCELED, RULE_MISSING_ORDER + ": no longer resting at the exchange", now));
            events.add(RiskAlertEvent.of(RULE_MISSING_ORDER, RiskAlertEvent.Severity.CRITICAL,
                    "order " + local.clientOrderId() + " (" + local.symbol().unified() + " "
                            + local.status() + ") is open locally but not at the exchange; cancelled locally",
                    now));
        }
        for (OpenOrder order : remote) {
            if (orders.find(order.clientOrderId()).isEmpty()) {
                // Reported, not adopted: we do not know its original parameters and inventing an
                // order row would give reconciliation something false to reconcile next time.
                events.add(RiskAlertEvent.of(RULE_UNKNOWN_ORDER, RiskAlertEvent.Severity.WARNING,
                        "exchange holds an unknown resting order " + order.clientOrderId() + " ("
                                + order.symbol().unified() + " " + order.side() + " " + order.qty().toPlainString()
                                + ")", now));
            }
        }
        return events;
    }

    private List<Event> reconcilePositions(List<Position> remote, long now) {
        Map<Symbol, Position> bySymbol = new LinkedHashMap<>();
        for (Position position : remote) {
            bySymbol.put(position.symbol(), position);
        }
        List<Event> events = new ArrayList<>();
        // Two different Position types meet here on purpose and must not be confused by name:
        // the remote ones are the exchange's statement (gateway.Position, above), the local ones are
        // our book's (common.portfolio.Position). `var` keeps the local side honest - naming the
        // type explicitly would import one of them and make the other an error at a distance.
        for (var local : portfolio.openPositions()) {
            Position theirs = bySymbol.get(local.symbol());
            BigDecimal remoteSigned = theirs == null ? BigDecimal.ZERO : signed(theirs);
            if (remoteSigned.compareTo(local.signedQty()) == 0) {
                continue;
            }
            portfolio.setPosition(local.symbol(), remoteSigned, theirs == null ? BigDecimal.ZERO : theirs.entryPrice());
            events.add(RiskAlertEvent.of(RULE_POSITION_MISMATCH, RiskAlertEvent.Severity.CRITICAL,
                    "position corrected for " + local.symbol().unified() + ": local "
                            + local.signedQty().toPlainString() + " vs exchange "
                            + remoteSigned.toPlainString(), now));
        }
        for (Position position : remote) {
            if (portfolio.position(position.symbol()).isEmpty()) {
                // The alert is unconditional: whether or not we act on it, an unexplained position is
                // something the operator has to be told about.
                events.add(RiskAlertEvent.of(RULE_GHOST_POSITION, RiskAlertEvent.Severity.CRITICAL,
                        ghostMessage(position), now));
                // Only a real position can be closed. A FLAT or zero-qty row is the exchange saying
                // "nothing here" in a shape that reached this loop, and ordering against it would be an
                // order for zero - a dirty order in everything but name.
                if (autoCloseGhostPositions && position.direction() != Direction.FLAT
                        && position.qty().signum() > 0) {
                    events.add(ghostCloseOrder(position, now));
                }
            }
        }
        return events;
    }

    private String ghostMessage(Position position) {
        String suffix = autoCloseGhostPositions
                ? " - auto-close is on: sending a MARKET order through the OMS to flatten it"
                : " - left open (auto-close disabled)";
        return "ghost position at the exchange: " + position.symbol().unified() + " "
                + position.direction() + " " + position.qty().toPlainString()
                + " @ " + position.entryPrice().toPlainString() + suffix;
    }

    /**
     * The order that flattens one ghost: the opposite side, the whole quantity, MARKET. MARKET for the
     * same reason the gate only ever sends MARKET - a limit price chosen here would be a quote nobody
     * asked for, and the position is unexplained, so waiting for a better price is not the priority.
     *
     * <p>The id is derived from the symbol rather than from the clock so repeated passes are
     * idempotent; see {@link #GHOST_CLOSE_ID_PREFIX}. The zero timestamp and sequence are deliberate:
     * {@code ClientOrderIds} normally spends them on uniqueness across orders, but here uniqueness
     * across passes is the property we want, and the symbol already supplies it.
     */
    private static OrderRequestEvent ghostCloseOrder(Position position, long now) {
        Side side = position.direction() == Direction.LONG ? Side.SELL : Side.BUY;
        String clientOrderId = ClientOrderIds.of(GHOST_CLOSE_ID_PREFIX + position.symbol().binance(), 0L, 0L);
        return OrderRequestEvent.of(clientOrderId, position.symbol(), side, OrderType.MARKET,
                position.qty(), null, now);
    }

    private List<Event> reconcileEquity(AccountSnapshot account, long now) {
        if (account == null) {
            return List.of();
        }
        BigDecimal local = portfolio.equity();
        BigDecimal remote = account.totalEquity();
        if (local.signum() == 0) {
            return List.of();
        }
        BigDecimal drift = remote.subtract(local).abs().divide(local, 8, RoundingMode.HALF_UP);
        if (drift.compareTo(equityTolerance) <= 0) {
            return List.of();
        }
        return List.of(RiskAlertEvent.of(RULE_EQUITY_MISMATCH, RiskAlertEvent.Severity.WARNING,
                "equity differs from the exchange by " + drift.multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP).toPlainString() + "%: local "
                        + local.toPlainString() + " vs exchange " + remote.toPlainString(), now));
    }

    private static BigDecimal signed(Position position) {
        return position.direction() == Direction.LONG ? position.qty() : position.qty().negate();
    }
}
