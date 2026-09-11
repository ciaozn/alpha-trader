package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.OrderStatus;
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
 * <p>What it does NOT do: it does not close positions. A ghost position - one the exchange holds and
 * we have no record of - is reported CRITICAL and left alone; closing it automatically is a P4
 * decision (spec FR-EX-05), and until then the honest behaviour is to make noise, because a position
 * we did not open may be one we do not understand.
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

    /** Default tolerance for "our equity and the exchange's disagree": 1%. */
    public static final BigDecimal DEFAULT_EQUITY_TOLERANCE = new BigDecimal("0.01");

    /** What the exchange reports, gathered once per pass by {@link ReconciliationRunner}. */
    public record ExchangeState(List<OpenOrder> openOrders, List<Position> positions, AccountSnapshot account) {
    }

    private final OrderStore orders;
    private final Portfolio portfolio;
    private final Clock clock;
    private final BigDecimal equityTolerance;

    public Reconciler(OrderStore orders, Portfolio portfolio, Clock clock) {
        this(orders, portfolio, clock, DEFAULT_EQUITY_TOLERANCE);
    }

    public Reconciler(OrderStore orders, Portfolio portfolio, Clock clock, BigDecimal equityTolerance) {
        this.orders = orders;
        this.portfolio = portfolio;
        this.clock = clock;
        this.equityTolerance = equityTolerance;
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
                events.add(RiskAlertEvent.of(RULE_GHOST_POSITION, RiskAlertEvent.Severity.CRITICAL,
                        "ghost position at the exchange: " + position.symbol().unified() + " "
                                + position.direction() + " " + position.qty().toPlainString()
                                + " @ " + position.entryPrice().toPlainString()
                                + " - left open (auto-close is P4)", now));
            }
        }
        return events;
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
