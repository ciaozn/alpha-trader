package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.OrderUpdateEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T312/T313: the order state machine and its intake, exercised over every path through it (FR-EX-01,
 * FR-EX-02).
 *
 * <p>The exhaustive test at the bottom is the one that carries the acceptance criterion 非法迁移一律
 * 拒绝. It states the machine independently of the production table - as a predicate over "where the
 * order is" and "what was attempted" - so editing {@code OrderManager.LEGAL} makes the two disagree
 * rather than making the test agree with itself. Everything above it is a named path a reader would
 * look for: what each report does to the row, what the row's numbers become, and what a refusal costs.
 *
 * <p>Three assertions here are about <b>order</b> rather than content, and all three are the kind of
 * thing that passes by accident if nobody writes it down. The row is written before the migration is
 * announced, which is checked by reading the store from inside the publisher. The row exists before the
 * order is handed over for sending, which is the same store read from inside the outbox - a crash the
 * other way round leaves an order on the exchange this process has no record of. And a redelivered
 * request neither moves the row nor reaches the outbox, which is FR-EX-02's two halves.
 *
 * <p>The outbox here records rather than sends. What a hand-over does once it leaves the engine thread
 * is {@code OrderSenderTest}'s subject, and mixing the two would make a gateway failure look like a
 * state-machine failure.
 */
class OrderManagerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final String ID = "ma-cross-btc-1700000000000-1";
    private static final String EXCHANGE_ID = "2639485123";
    private static final long T0 = 1_700_000_000_000L;

    /** Three contracts, so an order can fill in one piece, in three, or stop part way. */
    private static final BigDecimal QTY = new BigDecimal("3");
    private static final BigDecimal ONE = new BigDecimal("1");
    private static final BigDecimal PRICE = new BigDecimal("100");
    private static final BigDecimal FEE = new BigDecimal("0.05");

    private record Harness(OrderManager oms, InMemoryOrderStore store, List<Event> published,
                           RecordingOutbox outbox) {
    }

    /** Records the hand-over instead of performing it. Always accepts, so refusal is never confused
     *  with the OMS not asking. */
    private static final class RecordingOutbox implements OrderOutbox {

        private final List<OrderRequestEvent> handed = new ArrayList<>();

        @Override
        public boolean submit(OrderRequestEvent request) {
            handed.add(request);
            return true;
        }
    }

    private Harness harness() {
        return harness(request());
    }

    /**
     * @param request the event intake is given. Passed in rather than built here so a test can assert
     *                that this very object is what reached the outbox: {@code request()} mints a fresh
     *                {@code eventId} on every call, so two calls are never equal.
     */
    private Harness harness(OrderRequestEvent request) {
        InMemoryOrderStore store = new InMemoryOrderStore();
        List<Event> published = new ArrayList<>();
        RecordingOutbox outbox = new RecordingOutbox();
        Harness harness = new Harness(new OrderManager(store, outbox), store, published, outbox);
        harness.oms().onEvent(request, published::add);
        return harness;
    }

    private static OrderRequestEvent request() {
        return OrderRequestEvent.of(ID, BTC, Side.BUY, OrderType.MARKET, QTY, null, T0);
    }

    private OrderRecord row(Harness harness) {
        return harness.store().find(ID).orElseThrow();
    }

    private static List<OrderUpdateEvent> updates(List<Event> events) {
        return events.stream().filter(OrderUpdateEvent.class::isInstance)
                .map(OrderUpdateEvent.class::cast).toList();
    }

    private static List<RiskAlertEvent> alerts(List<Event> events) {
        return events.stream().filter(RiskAlertEvent.class::isInstance)
                .map(RiskAlertEvent.class::cast).toList();
    }

    /**
     * What the exchange can say about an order, and the status each one aims at. A trade aims at the
     * status its own arithmetic produces - a partial one leaves a remainder, a full one does not - and
     * every case below is built so the aim and the outcome agree, which is what lets one predicate
     * judge all thirty-six. {@code RESTATE_NEW} is the one that aims backwards: without it nothing in
     * the set asks for NEW, and a table that lets NEW succeed itself is indistinguishable from one
     * that does not.
     */
    private enum Attempt {
        ACK(OrderStatus.SUBMITTED),
        CANCEL(OrderStatus.CANCELED),
        REJECT(OrderStatus.REJECTED),
        RESTATE_NEW(OrderStatus.NEW),
        PARTIAL_TRADE(OrderStatus.PARTIALLY_FILLED),
        FULL_TRADE(OrderStatus.FILLED);

        private final OrderStatus target;

        Attempt(OrderStatus target) {
            this.target = target;
        }

        boolean isTrade() {
            return this == PARTIAL_TRADE || this == FULL_TRADE;
        }

        OrderReportEvent report(long businessTs) {
            return switch (this) {
                case ACK -> OrderReportEvent.of(ID, EXCHANGE_ID, OrderStatus.SUBMITTED, null, businessTs);
                case CANCEL -> OrderReportEvent.of(ID, EXCHANGE_ID, OrderStatus.CANCELED,
                        "client canceled", businessTs);
                case REJECT -> OrderReportEvent.of(ID, null, OrderStatus.REJECTED,
                        "insufficient balance", businessTs);
                case RESTATE_NEW -> OrderReportEvent.of(ID, EXCHANGE_ID, OrderStatus.NEW,
                        "still resting", businessTs);
                case PARTIAL_TRADE -> OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE, PRICE, FEE, businessTs);
                case FULL_TRADE -> OrderReportEvent.ofTrade(ID, EXCHANGE_ID, QTY, PRICE, FEE, businessTs);
            };
        }
    }

    // ------------------------------------------------------------------ the happy paths

    @Test
    void anOrderRequestOpensANewRowAndAnnouncesIt() {
        Harness harness = harness();

        OrderRecord row = row(harness);
        assertThat(row.clientOrderId()).isEqualTo(ID);
        assertThat(row.symbol()).isEqualTo(BTC);
        assertThat(row.side()).isEqualTo(Side.BUY);
        assertThat(row.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(row.qty()).isEqualTo(QTY);
        assertThat(row.status()).isEqualTo(OrderStatus.NEW);
        // Nothing the exchange has said yet, so nothing invented: no id, no average, no explanation.
        assertThat(row.exchangeOrderId()).isNull();
        assertThat(row.avgFillPrice()).isNull();
        assertThat(row.statusMessage()).isNull();
        assertThat(row.filledQty()).isEqualTo(new BigDecimal("0.00000000"));
        // Equal timestamps are what makes "this order has never moved" readable off the row.
        assertThat(row.createdAt()).isEqualTo(T0);
        assertThat(row.updatedAt()).isEqualTo(T0);

        assertThat(updates(harness.published())).hasSize(1);
        OrderUpdateEvent update = updates(harness.published()).getFirst();
        assertThat(update.clientOrderId()).isEqualTo(ID);
        assertThat(update.status()).isEqualTo(OrderStatus.NEW);
        assertThat(update.filledQty()).isEqualTo(new BigDecimal("0.00000000"));
        assertThat(update.avgPrice()).isNull();
        assertThat(update.timestamp()).isEqualTo(T0);
        assertThat(alerts(harness.published())).isEmpty();
    }

    @Test
    void anAcceptedAckMovesTheOrderToSubmittedAndRecordsTheExchangeId() {
        Harness harness = harness();

        harness.oms().onEvent(Attempt.ACK.report(T0 + 1), harness.published()::add);

        OrderRecord row = row(harness);
        assertThat(row.status()).isEqualTo(OrderStatus.SUBMITTED);
        // The id reconciliation asks the exchange with (FR-EX-04); without it the order is unreachable.
        assertThat(row.exchangeOrderId()).isEqualTo(EXCHANGE_ID);
        assertThat(row.updatedAt()).isEqualTo(T0 + 1);
        assertThat(row.createdAt()).as("a migration moves the row, not its place in history").isEqualTo(T0);
        assertThat(row.filledQty()).isEqualTo(new BigDecimal("0.00000000"));
        assertThat(updates(harness.published())).hasSize(2);
        assertThat(updates(harness.published()).get(1).status()).isEqualTo(OrderStatus.SUBMITTED);
    }

    @Test
    void aRejectedAckMovesTheOrderToRejectedAndKeepsTheReason() {
        Harness harness = harness();

        harness.oms().onEvent(Attempt.REJECT.report(T0 + 1), harness.published()::add);

        OrderRecord row = row(harness);
        assertThat(row.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(row.statusMessage()).isEqualTo("insufficient balance");
        assertThat(row.exchangeOrderId()).as("a rejected order was never given one").isNull();
        assertThat(row.filledQty()).isEqualTo(new BigDecimal("0.00000000"));
        assertThat(row.avgFillPrice()).isNull();
    }

    @Test
    void aTradeThatLeavesARemainderIsPartiallyFilled() {
        Harness harness = harness();

        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 1), harness.published()::add);

        OrderRecord row = row(harness);
        assertThat(row.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(row.filledQty()).isEqualTo(new BigDecimal("1.00000000"));
        assertThat(row.avgFillPrice()).isEqualByComparingTo("100");
        assertThat(row.updatedAt()).isEqualTo(T0 + 1);
        assertThat(updates(harness.published()).getLast().filledQty())
                .isEqualTo(new BigDecimal("1.00000000"));
    }

    @Test
    void tradesAverageTheirPricesAndTheLastOneFillsTheOrder() {
        Harness harness = harness();

        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 1), harness.published()::add);
        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE,
                new BigDecimal("200"), FEE, T0 + 2), harness.published()::add);
        assertThat(row(harness).status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(row(harness).filledQty()).isEqualTo(new BigDecimal("2.00000000"));
        // One at 100 and one at 200. The value is the contract; the scale is Money.divide's, which is
        // the same arithmetic Portfolio uses for an entry price, so the two cannot round differently.
        assertThat(row(harness).avgFillPrice()).isEqualByComparingTo("150");

        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE,
                new BigDecimal("300"), FEE, T0 + 3), harness.published()::add);

        OrderRecord row = row(harness);
        assertThat(row.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(row.filledQty()).isEqualTo(QTY.setScale(8));
        assertThat(row.avgFillPrice()).isEqualByComparingTo("200");
        assertThat(updates(harness.published())).hasSize(4);
        assertThat(alerts(harness.published())).isEmpty();
    }

    @Test
    void anOrderFilledInOneTradeGoesStraightFromNewToFilled() {
        // The forward skip the table allows on purpose: a restart that missed the ack still has to be
        // able to record the trade, and inventing a SUBMITTED in between would be a fact that never
        // happened.
        Harness harness = harness();

        harness.oms().onEvent(Attempt.FULL_TRADE.report(T0 + 1), harness.published()::add);

        OrderRecord row = row(harness);
        assertThat(row.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(row.filledQty()).isEqualTo(QTY.setScale(8));
        assertThat(row.avgFillPrice()).isEqualByComparingTo("100");
        // The execution may be the first thing ever heard about this order, so its id can be the only
        // handle reconciliation ever gets (FR-EX-04): a trade has to record it, not just move the row.
        assertThat(row.exchangeOrderId()).isEqualTo(EXCHANGE_ID);
        assertThat(row.updatedAt()).isEqualTo(T0 + 1);
    }

    @Test
    void cancelingTheRemainderKeepsThePartThatFilled() {
        Harness harness = harness();
        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 1), harness.published()::add);

        harness.oms().onEvent(Attempt.CANCEL.report(T0 + 2), harness.published()::add);

        OrderRecord row = row(harness);
        assertThat(row.status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(row.statusMessage()).isEqualTo("client canceled");
        // Canceling the remainder cannot un-trade the part that filled: that money moved, and a row
        // that dropped it would disagree with the book T314 writes from the same reports.
        assertThat(row.filledQty()).isEqualTo(new BigDecimal("1.00000000"));
        assertThat(row.avgFillPrice()).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------ order of operations

    @Test
    void theRowIsWrittenBeforeTheMigrationIsAnnounced() {
        InMemoryOrderStore store = new InMemoryOrderStore();
        List<Event> published = new ArrayList<>();
        List<OrderStatus> statusSeenByThePublisher = new ArrayList<>();
        OrderManager oms = new OrderManager(store, request -> true);

        // The publisher reads the store rather than trusting the event: if an announcement went out
        // first, this would find no row at all on the open, and would see NEW while the event said
        // SUBMITTED on the ack. Both paths are driven because they write and publish separately.
        EventPublisher publisher = event -> {
            published.add(event);
            if (event instanceof OrderUpdateEvent update) {
                statusSeenByThePublisher.add(store.find(ID).orElseThrow().status());
                assertThat(update.status()).isEqualTo(statusSeenByThePublisher.getLast());
            }
        };

        oms.onEvent(request(), publisher);
        oms.onEvent(Attempt.ACK.report(T0 + 1), publisher);

        assertThat(statusSeenByThePublisher).containsExactly(OrderStatus.NEW, OrderStatus.SUBMITTED);
        assertThat(updates(published)).hasSize(2);
    }

    @Test
    void theRowExistsBeforeTheOrderIsHandedOverForSending() {
        InMemoryOrderStore store = new InMemoryOrderStore();
        List<Event> published = new ArrayList<>();
        List<OrderStatus> statusAtHandOver = new ArrayList<>();
        OrderManager oms = new OrderManager(store, request -> {
            statusAtHandOver.add(store.find(request.clientOrderId()).orElseThrow().status());
            return true;
        });

        oms.onEvent(request(), published::add);

        // A crash between the two leaves an unsent row that reconciliation finds. The other way round
        // leaves an order resting on the exchange that this process has no record of, which is FR-EX-04's
        // ghost and the one that can cost money.
        assertThat(statusAtHandOver).containsExactly(OrderStatus.NEW);
        assertThat(updates(published)).hasSize(1);
    }

    @Test
    void anOrderIsHandedOverOnceAndOnlyByIntake() {
        OrderRequestEvent order = request();
        Harness harness = harness(order);
        assertThat(harness.outbox().handed).containsExactly(order);

        // Reports move the row; only intake sends. Handing over again on the ack would place the same
        // clientOrderId twice, and the exchange would answer the second one with a rejection for an
        // order that is live - indistinguishable from a genuine refusal.
        harness.oms().onEvent(Attempt.ACK.report(T0 + 1), harness.published()::add);
        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 2), harness.published()::add);
        harness.oms().onEvent(Attempt.CANCEL.report(T0 + 3), harness.published()::add);

        assertThat(harness.outbox().handed).containsExactly(order);
    }

    @Test
    void aRedeliveredOrderRequestLeavesTheRowExactlyAsItWas() {
        OrderRequestEvent order = request();
        Harness harness = harness(order);
        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 1), harness.published()::add);
        OrderRecord traded = row(harness);
        harness.published().clear();

        // A replayed journal rebuilds every event with a fresh eventId, so this is a different object
        // carrying the same clientOrderId - the key FR-EX-02 is written against. Overwriting the row
        // would reset filledQty to zero on an order that has already traded.
        harness.oms().onEvent(request(), harness.published()::add);

        assertThat(row(harness)).isEqualTo(traded);
        assertThat(harness.published()).isEmpty();
        // The send half of FR-EX-02. The outbox keeps its own set of handed-over ids as a last line,
        // but returning here is what stops a replayed request from ever reaching it.
        assertThat(harness.outbox().handed).containsExactly(order);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aReportAboutAnOrderThisProcessNeverSentIsRefusedAndSaysSo() {
        Harness harness = harness();
        harness.published().clear();

        harness.oms().onEvent(OrderReportEvent.of("some-other-process-1", EXCHANGE_ID,
                OrderStatus.SUBMITTED, null, T0 + 1), harness.published()::add);

        assertThat(harness.store().find("some-other-process-1")).isEmpty();
        assertThat(harness.store().findOpen()).hasSize(1);
        assertThat(updates(harness.published())).isEmpty();
        assertThat(alerts(harness.published())).hasSize(1);
        RiskAlertEvent alert = alerts(harness.published()).getFirst();
        assertThat(alert.ruleId()).isEqualTo(OrderManager.RULE_UNKNOWN_ORDER);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(alert.detail()).contains("some-other-process-1").contains("never sent");
        assertThat(alert.timestamp()).isEqualTo(T0 + 1);
    }

    @Test
    void aLifecycleReportCannotClaimEitherFillStatus() {
        // Both halves. A guard that only refused FILLED would let a report claim PARTIALLY_FILLED and
        // produce a row saying part of this order traded when nothing did - the same lie, smaller.
        for (OrderStatus claimed : List.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED)) {
            Harness harness = harness();
            harness.published().clear();

            // The row's filled quantity has to come from an execution: a status alone carries no price
            // and no quantity, so accepting it would produce an order that says FILLED with nothing
            // filled.
            harness.oms().onEvent(OrderReportEvent.of(ID, EXCHANGE_ID, claimed, null, T0 + 1),
                    harness.published()::add);

            assertThat(row(harness).status()).as("%s", claimed).isEqualTo(OrderStatus.NEW);
            assertThat(row(harness).filledQty()).isEqualTo(new BigDecimal("0.00000000"));
            assertThat(updates(harness.published())).isEmpty();
            assertThat(alerts(harness.published())).hasSize(1);
            RiskAlertEvent alert = alerts(harness.published()).getFirst();
            assertThat(alert.ruleId()).isEqualTo(OrderManager.RULE_REFUSED_TRANSITION);
            assertThat(alert.severity())
                    .as("no execution was refused here, so nothing is missing from the book")
                    .isEqualTo(RiskAlertEvent.Severity.WARNING);
            // "is NEW and cannot become X", in that order: an alert that named the two the other way
            // round would read as a complaint about the state the order is not in.
            assertThat(alert.detail()).contains("is NEW")
                    .contains("cannot become " + claimed).contains("execution");
        }
    }

    @Test
    void aRefusedTradeIsCriticalAndALateAckIsOnlyAWarning() {
        Harness filled = driveTo(OrderStatus.FILLED);
        filled.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 9), filled.published()::add);
        RiskAlertEvent trade = alerts(filled.published()).getFirst();
        assertThat(trade.ruleId()).isEqualTo(OrderManager.RULE_REFUSED_TRANSITION);
        // An execution the row will not reflect: the book and the exchange now disagree about a
        // position, which is FR-EX-04's emergency rather than a curiosity.
        assertThat(trade.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(trade.detail()).contains("FILLED").contains("execution");

        Harness late = driveTo(OrderStatus.FILLED);
        late.oms().onEvent(Attempt.ACK.report(T0 + 9), late.published()::add);
        RiskAlertEvent ack = alerts(late.published()).getFirst();
        assertThat(ack.severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        // The alert has to say where the order actually is, and in that order: "is SUBMITTED and cannot
        // become FILLED" contains the same two words and describes a different order.
        assertThat(ack.detail()).contains(ID)
                .contains("is FILLED").contains("cannot become SUBMITTED");
        assertThat(row(late).status()).isEqualTo(OrderStatus.FILLED);
        assertThat(updates(late.published())).isEmpty();
    }

    // ------------------------------------------------------------------ the whole machine

    /**
     * Every state the machine can be in, against everything the exchange can say: thirty-six attempts,
     * twelve of them accepted. This is the acceptance criterion's 全路径 and 非法迁移一律拒绝 in one
     * place, and it is written as a predicate over {@code from} and {@code target} rather than as a
     * copy of {@code OrderManager.LEGAL} so that the two have to agree rather than one having to
     * exist.
     *
     * <p>The driver asserts the state it was asked to produce, because a driver that silently failed
     * would turn every case into the same one and the test would still be green.
     */
    @Test
    void everyAttemptFromEveryStateEitherMovesTheRowOrRefusesIt() {
        for (OrderStatus from : OrderStatus.values()) {
            for (Attempt attempt : Attempt.values()) {
                Harness harness = driveTo(from);
                assertThat(row(harness).status()).isEqualTo(from);

                harness.oms().onEvent(attempt.report(T0 + 9), harness.published()::add);

                boolean shouldMove = moves(from, attempt.target);
                String where = from + " +" + attempt;
                if (shouldMove) {
                    OrderRecord moved = row(harness);
                    assertThat(moved.status()).as(where).isEqualTo(attempt.target);
                    assertThat(moved.updatedAt()).as(where).isEqualTo(T0 + 9);
                    assertThat(updates(harness.published())).as(where).hasSize(1);
                    // The announcement describes the row, so it is compared to the row rather than to
                    // what the attempt asked for: an event quoting the order's quantity, or its
                    // creation time, is still a well-formed OrderUpdateEvent and reads like the truth.
                    OrderUpdateEvent update = updates(harness.published()).getFirst();
                    assertThat(update.clientOrderId()).as(where).isEqualTo(moved.clientOrderId());
                    assertThat(update.status()).as(where).isEqualTo(moved.status());
                    assertThat(update.filledQty()).as(where).isEqualTo(moved.filledQty());
                    assertThat(update.avgPrice()).as(where).isEqualTo(moved.avgFillPrice());
                    assertThat(update.timestamp()).as(where).isEqualTo(moved.updatedAt());
                    assertThat(alerts(harness.published())).as(where).isEmpty();
                } else {
                    assertThat(row(harness).status()).as(where).isEqualTo(from);
                    assertThat(row(harness).updatedAt()).as(where).isNotEqualTo(T0 + 9);
                    assertThat(updates(harness.published())).as(where).isEmpty();
                    assertThat(alerts(harness.published())).as(where).hasSize(1);
                    assertThat(alerts(harness.published()).getFirst().ruleId()).as(where)
                            .isEqualTo(OrderManager.RULE_REFUSED_TRANSITION);
                    assertThat(alerts(harness.published()).getFirst().severity()).as(where)
                            .isEqualTo(attempt.isTrade()
                                    ? RiskAlertEvent.Severity.CRITICAL
                                    : RiskAlertEvent.Severity.WARNING);
                }
            }
        }
    }

    /**
     * The machine, restated. Nothing moves out of a terminal state; nothing moves to NEW, because NEW
     * is the state a request opens in and being told an order is still new is a duplicate report saying
     * nothing new - from NEW itself as much as from anywhere else. From a live state anything else
     * forward moves, and the two refusals inside the live states are the ones with a reason: a second
     * ack says nothing new, and a rejection claims nothing traded on an order that already did.
     */
    private static boolean moves(OrderStatus from, OrderStatus target) {
        if (from.isTerminal() || target == OrderStatus.NEW) {
            return false;
        }
        return switch (from) {
            case NEW -> true;
            case SUBMITTED -> target != OrderStatus.SUBMITTED;
            case PARTIALLY_FILLED ->
                    target != OrderStatus.SUBMITTED && target != OrderStatus.REJECTED;
            default -> false;
        };
    }

    /**
     * A fresh OMS whose one order is already in {@code status}, with the events that got it there
     * cleared so each case asserts only what its own attempt produced.
     */
    private Harness driveTo(OrderStatus status) {
        Harness harness = harness();
        Attempt driver = switch (status) {
            case NEW -> null;
            case SUBMITTED -> Attempt.ACK;
            case PARTIALLY_FILLED -> Attempt.PARTIAL_TRADE;
            case FILLED -> Attempt.FULL_TRADE;
            case CANCELED -> Attempt.CANCEL;
            case REJECTED -> Attempt.REJECT;
        };
        if (driver != null) {
            harness.oms().onEvent(driver.report(T0 + 1), harness.published()::add);
        }
        harness.published().clear();
        return harness;
    }
}
