package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.OrderUpdateEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The order state machine (FR-EX-01). It occupies in paper and live exactly the slot
 * {@code SimulatedExecutor} occupies in backtest: both eat an {@link OrderRequestEvent} and both are
 * the component that knows an execution happened (取舍 16).
 *
 * <p><b>Every accepted report writes all of its state before publishing any of its events.</b> In its
 * simplest instance - a lifecycle report - that is one row and one {@link OrderUpdateEvent}, and the row
 * goes first so that anything reacting to the event reads a row that already says what the event says:
 * an observer that can see the announcement before the state is a component whose behaviour depends on
 * registration order. The trade report has more state and follows the same rule, below.
 *
 * <p><b>Refusals are events, not exceptions.</b> An illegal migration is what a duplicated report
 * from a reconnecting stream, a late ack for an order that already filled, or a correction from
 * reconciliation looks like - all of them ordinary in live trading. Throwing would put the failure on
 * the engine thread, where {@code EventEngine.dispatch} catches it, logs it and moves on: the row
 * would be unchanged and nothing downstream would know. Now that a trade also moves the book, throwing
 * is worse than silent - {@code Portfolio.applyFill} refuses a non-positive quantity, and an exception
 * from it after the row was written leaves the row claiming an execution the book never saw. So a report
 * whose numbers cannot be interpreted is refused before anything is computed, the OMS writes nothing,
 * publishes nothing about the order, and says so on the alert channel the rest of the system already
 * uses.
 *
 * <p><b>The send happens after the row is written, and on another thread.</b> {@code placeOrder} is a
 * blocking REST call and {@code EventHandler} forbids network IO here, so the OMS hands the order to an
 * {@link OrderOutbox} and {@code OrderSender} makes the call on its own thread, publishing whatever the
 * exchange says back as an {@link OrderReportEvent}. That the row exists first is what makes the answer
 * findable when it arrives; that the hand-over comes last is what keeps a crash between the two on the
 * safe side - an unsent row rather than a sent order nobody recorded. This class holds no gateway and
 * no clock, and never learns whether the exchange answered.
 *
 * <p><b>On a trade report the OMS is also the bookkeeper (取舍 16, FR-EX-06)</b>, occupying the same
 * slot {@code SimulatedExecutor} occupies in backtest: whoever is certain an execution happened writes
 * it into {@code Portfolio} <em>before</em> publishing the {@code FillEvent}. That ordering is a
 * cross-module contract, not a local preference - {@code CircuitBreaker.onFill} reads
 * {@code portfolio.realizedPnl()} on the arrival of the event and treats the change since the last one
 * as this trade's result, so a book updated afterwards makes every closing trade read as a zero and the
 * losing streak never breaks. The four writes go fill row, order row, book, events: the durable ones
 * first because they are the ones that can fail, and the append-only fill before the row because a
 * failure then leaves the row still open and the report unapplied, where the other order can leave a row
 * saying FILLED - terminal, so {@code findOpen()} never returns it again - with no position behind it.
 * Of the two events the fill goes first because it is the cause and the
 * migration is its consequence, which is the order a journal reader needs to follow.
 *
 * <p>Runs on the event-engine thread only, like every handler, so the store and the book it writes need
 * no locking. Exchange reports reach it as events rather than as calls from a socket thread for the same
 * reason: {@code EventHandler}'s contract forbids network IO on this thread, and
 * {@code EventEngine.publish} is the one thread-safe way in.
 */
public final class OrderManager implements EventHandler {

    /** The machine refused to move an order. The row keeps saying what the OMS actually knows. */
    public static final String RULE_REFUSED_TRANSITION = "EX-refused-transition";
    /** A report about an order this process never held, so there is no row to move. */
    public static final String RULE_UNKNOWN_ORDER = "EX-unknown-order";
    /**
     * An execution whose numbers cannot be interpreted: a quantity that is not positive, or a price that
     * is missing or not positive. Kept apart from {@link #RULE_REFUSED_TRANSITION} because the two have
     * different causes and different fixes - that one says a well-formed report arrived at the wrong
     * moment, this one says the report itself is not to be trusted.
     */
    public static final String RULE_MALFORMED_TRADE = "EX-malformed-trade";

    private static final Logger log = LoggerFactory.getLogger(OrderManager.class);

    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL = legalTransitions();

    private final OrderStore store;
    private final OrderOutbox outbox;
    private final Portfolio portfolio;

    public OrderManager(OrderStore store, OrderOutbox outbox, Portfolio portfolio) {
        this.store = store;
        this.outbox = outbox;
        this.portfolio = portfolio;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        switch (event) {
            case OrderRequestEvent request -> open(request, publisher);
            case OrderReportEvent report -> onReport(report, publisher);
            // K-lines, signals, alerts, and the OMS's own OrderUpdateEvents: not the order book's
            // business. It never reads back what it published, which is what keeps one migration from
            // becoming two entries in the journal.
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ intake

    /**
     * An order that survived the risk gate. This is the one path that creates a row rather than
     * moving one, because NEW is the state the request opens in and there is no earlier row for it to
     * be an illegal move from.
     */
    private void open(OrderRequestEvent request, EventPublisher publisher) {
        if (store.find(request.clientOrderId()).isPresent()) {
            // NEW -> NEW is not in the table, and saving anyway would reset filledQty on an order that
            // has already traded. This is the storage half of FR-EX-02's idempotency; the send half is
            // the sender's own set of handed-over ids, and returning here is what keeps a redelivered
            // request from ever reaching it.
            log.warn("Ignoring a redelivered order request [{}]", request.clientOrderId());
            return;
        }
        OrderRecord row = OrderRecord.ofNew(request, request.timestamp());
        store.save(row);
        publisher.publish(announcement(row));
        log.debug("Order {} opened: {} {} {} qty={}", row.clientOrderId(), row.symbol().unified(),
                row.side(), row.orderType(), row.qty().toPlainString());
        // Last, and off this thread: the row has to exist before anything can answer about it, and the
        // answer arrives as an event in a later round whichever way the send goes.
        outbox.submit(request);
    }

    private void onReport(OrderReportEvent report, EventPublisher publisher) {
        Optional<OrderRecord> found = store.find(report.clientOrderId());
        if (found.isEmpty()) {
            // Sent before a restart, or sent by something else on the same account. Inventing a row
            // here would mean guessing the quantity and the side; reconciliation owns the difference
            // because it is the component that asks the exchange what it actually holds.
            alert(publisher, RULE_UNKNOWN_ORDER, RiskAlertEvent.Severity.WARNING,
                    "order report for " + report.clientOrderId() + ", which this process never sent",
                    report.timestamp());
            return;
        }

        OrderRecord current = found.get();
        if (report.isTrade()) {
            onTrade(current, report, publisher);
        } else {
            onLifecycle(current, report, publisher);
        }
    }

    private void onLifecycle(OrderRecord current, OrderReportEvent report, EventPublisher publisher) {
        if (claimsAFill(report.status())) {
            refuse(publisher, current, report.status(), report.timestamp(),
                    RiskAlertEvent.Severity.WARNING,
                    "a lifecycle report cannot claim " + report.status()
                            + " - the row's filled quantity has to come from an execution");
            return;
        }

        OrderRecord next = current.after(report.status(), report.exchangeOrderId(), null, null,
                report.message(), report.timestamp());
        if (!LEGAL.get(current.status()).contains(next.status())) {
            refuse(publisher, current, next.status(), report.timestamp(),
                    RiskAlertEvent.Severity.WARNING, "the exchange reported it");
            return;
        }

        store.save(next);
        publisher.publish(announcement(next));
    }

    /**
     * One execution: the only report that moves money, so the only one with something to get wrong.
     * The symbol and the side come from the row rather than from the report, because a trade report
     * carries neither - which is also why an order this process never held can never reach here: without
     * the row there is nothing to say which instrument moved or in which direction.
     */
    private void onTrade(OrderRecord current, OrderReportEvent report, EventPublisher publisher) {
        String unusable = unusableTrade(report);
        if (unusable != null) {
            alert(publisher, RULE_MALFORMED_TRADE, RiskAlertEvent.Severity.CRITICAL,
                    "execution report for " + current.clientOrderId() + " " + unusable
                            + ", so nothing was written: the order stays " + current.status()
                            + " and reconciliation decides",
                    report.timestamp());
            return;
        }

        // Canonical scale, once, for all three inputs. These are the numbers the fill row, the order row
        // and the book are each handed, so normalizing them here makes the three hold the same bits rather
        // than three numbers that merely compare equal - and makes the book's entry price for a first
        // trade exactly the price the row reports. Money's contract is scale 8 for stored values, and a
        // TEXT column holds whatever scale it is given.
        BigDecimal price = Money.of(report.lastPrice());
        BigDecimal qty = Money.of(report.lastQty());
        // A missing commission is not a missing execution. Zero goes into both the stored fill and the
        // book, so the two agree with each other and differ only from the exchange - a difference
        // reconciliation can find, unlike a null in one and a zero in the other.
        BigDecimal fee = report.fee() == null ? Money.zero() : Money.of(report.fee());

        OrderRecord next = afterTrade(current, report, price, qty);
        if (!LEGAL.get(current.status()).contains(next.status())) {
            refuse(publisher, current, next.status(), report.timestamp(),
                    RiskAlertEvent.Severity.CRITICAL,
                    "an execution of " + qty.toPlainString() + " arrived for it");
            return;
        }

        FillEvent fill = FillEvent.of(next.clientOrderId(), next.symbol(), next.side(),
                price, qty, fee, report.timestamp());

        // Durable writes before the in-memory one, and the append-only fill before the order row: after
        // any failure the book is untouched and the row is still open, so reconciliation revisits it. The
        // other order leaves a worse residue - a row already saying FILLED, which is terminal, so
        // findOpen() never returns it again, with no fill behind it and no position in the book.
        store.saveFill(fill);
        store.save(next);
        Portfolio.FillResult result = portfolio.applyFill(next.symbol(), next.side(), price, qty, fee);
        publisher.publish(fill);
        publisher.publish(announcement(next));
        log.debug("Order {} executed {} at {}: now {} {} {}, position {} {}, realized {}",
                next.clientOrderId(), qty.toPlainString(), price.toPlainString(),
                next.filledQty().toPlainString(), next.side(), next.status(),
                result.position().direction(), result.position().qty().toPlainString(),
                result.realizedPnl().toPlainString());
    }

    /**
     * Why this trade report cannot be applied, or null when it can. Both checks carry weight and neither
     * is padding around a call that would have coped: {@link #afterTrade} divides the weighted price by
     * the cumulative filled quantity, so a zero-quantity report against an order that has never traded
     * is a division by zero, and {@code isTrade()} keys on the quantity alone, so a report can carry one
     * and no price at all.
     */
    private static String unusableTrade(OrderReportEvent report) {
        if (report.lastQty().signum() <= 0) {
            return "claims an execution of " + report.lastQty().toPlainString();
        }
        if (report.lastPrice() == null || report.lastPrice().signum() <= 0) {
            return "claims an execution at price " + report.lastPrice();
        }
        return null;
    }

    /**
     * The row after one execution. The cumulative filled quantity and the average price are computed
     * here rather than taken from the report: the report carries the last trade, and the row is what
     * knows about the ones before it. The average is the same weighted mean {@code Portfolio} uses for an
     * entry price, and the two arrive at it by different recurrences - the row from its own cumulative
     * quantity, the book from the position it holds - which is exactly why a test compares them.
     *
     * <p>They are not bit-identical, and cannot be made so from here. {@code Money.divide} answers at 24
     * significant digits; the row rounds that to {@code Money}'s stored scale, because its column is TEXT
     * and a TEXT column holds whatever scale it is given; {@code Portfolio} normalizes its cash, its
     * realized P&L and its fees but not its entry price. So when the quotient does not terminate at the
     * eighth decimal the two answers differ - by less than half a unit there, which is finer than any
     * exchange filter, and is bounded by a test rather than left for reconciliation to discover.
     *
     * <p>{@code price} and {@code qty} are the caller's normalized values, so the divisor below is never
     * zero: the guard that refuses an uninterpretable report has already run.
     *
     * <p>Whether the order is now partially or fully filled follows from its own quantity, which is
     * why {@link OrderReportEvent#ofTrade} carries no status. An order that trades past its quantity
     * is FILLED, not an error - the excess is reconciliation's to find.
     */
    private static OrderRecord afterTrade(OrderRecord row, OrderReportEvent report,
                                          BigDecimal price, BigDecimal qty) {
        BigDecimal filled = row.filledQty().add(qty);
        BigDecimal traded = price.multiply(qty, Money.MC);
        BigDecimal before = row.avgFillPrice() == null
                ? Money.zero()
                : row.avgFillPrice().multiply(row.filledQty(), Money.MC);
        BigDecimal average = Money.of(Money.divide(before.add(traded), filled));
        OrderStatus status = filled.compareTo(row.qty()) < 0
                ? OrderStatus.PARTIALLY_FILLED
                : OrderStatus.FILLED;
        return row.after(status, report.exchangeOrderId(), filled, average, null, report.timestamp());
    }

    private static boolean claimsAFill(OrderStatus status) {
        return status == OrderStatus.PARTIALLY_FILLED || status == OrderStatus.FILLED;
    }

    // ------------------------------------------------------------------ the machine

    /**
     * The whole state machine, as a table rather than as code paths, so "which migrations are legal"
     * has one answer and a test can enumerate it. FR-EX-01's chain names the happy path; what makes
     * it a machine is what it refuses.
     *
     * <p><b>Forward skips are allowed, backward moves are not.</b> NEW &rarr; FILLED looks like a
     * missing step, but it is what reconciliation reports after a restart that missed both the ack and
     * the trade. Refusing it would leave the row saying NEW while the exchange has already moved the
     * position - the one divergence FR-EX-04 exists to prevent - and the alternative, inventing a
     * SUBMITTED in between, writes a fact that never happened.
     *
     * <p><b>The three terminal states have no successors at all</b>, which is the acceptance
     * criterion's 终态不可再迁移. A filled order cannot be canceled and a canceled one cannot fill;
     * an exchange that says otherwise is describing a different order.
     *
     * <p><b>PARTIALLY_FILLED &rarr; PARTIALLY_FILLED is the one self-loop.</b> An order that fills in
     * pieces reports the same status several times, and refusing the second piece would strand the
     * remainder. Every other self-loop is a duplicate report saying nothing new.
     *
     * <p><b>PARTIALLY_FILLED &rarr; REJECTED is refused on purpose.</b> REJECTED means the exchange
     * never accepted the order, so nothing traded; once part of it has executed the remainder is
     * CANCELED. Reporting that as a rejection would erase the fact that money moved, and erase it in
     * the one column - {@code statusMessage} - a human reads first.
     */
    private static Map<OrderStatus, Set<OrderStatus>> legalTransitions() {
        Map<OrderStatus, Set<OrderStatus>> legal = new EnumMap<>(OrderStatus.class);
        legal.put(OrderStatus.NEW, EnumSet.of(OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED, OrderStatus.CANCELED, OrderStatus.REJECTED));
        legal.put(OrderStatus.SUBMITTED, EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
                OrderStatus.CANCELED, OrderStatus.REJECTED));
        legal.put(OrderStatus.PARTIALLY_FILLED, EnumSet.of(OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED, OrderStatus.CANCELED));
        legal.put(OrderStatus.FILLED, EnumSet.noneOf(OrderStatus.class));
        legal.put(OrderStatus.CANCELED, EnumSet.noneOf(OrderStatus.class));
        legal.put(OrderStatus.REJECTED, EnumSet.noneOf(OrderStatus.class));
        return Collections.unmodifiableMap(legal);
    }

    // ------------------------------------------------------------------ outcomes

    private static OrderUpdateEvent announcement(OrderRecord row) {
        return OrderUpdateEvent.of(row.clientOrderId(), row.status(), row.filledQty(),
                row.avgFillPrice(), row.updatedAt());
    }

    /**
     * A migration the machine will not make: nothing is written - neither the row nor, on a trade, the
     * book - nothing is published about the order, and the alert is what keeps that from being silent.
     *
     * <p><b>Severity splits on whether money was involved.</b> A refused trade means an execution the
     * row will not reflect, so the book and the exchange now disagree about a position - CRITICAL, the
     * same class as an untrustworthy input. A refused lifecycle report usually means the exchange is
     * behind us, a late ack for an order that already filled, and the row is the better answer -
     * WARNING, with reconciliation as the backstop that escalates if the row turns out to be wrong.
     */
    private void refuse(EventPublisher publisher, OrderRecord current, OrderStatus attempted,
                        long businessTs, RiskAlertEvent.Severity severity, String why) {
        String detail = "order " + current.clientOrderId() + " is " + current.status()
                + " and cannot become " + attempted + ": " + why;
        alert(publisher, RULE_REFUSED_TRANSITION, severity, detail, businessTs);
    }

    private void alert(EventPublisher publisher, String ruleId, RiskAlertEvent.Severity severity,
                       String detail, long businessTs) {
        log.warn("OMS [{}] {}", ruleId, detail);
        publisher.publish(RiskAlertEvent.of(ruleId, severity, detail, businessTs));
    }
}
