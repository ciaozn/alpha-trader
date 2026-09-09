package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.OrderUpdateEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T312-T314: the order state machine, its intake, and the book a trade report writes (FR-EX-01,
 * FR-EX-02, FR-EX-06).
 *
 * <p>The exhaustive test at the bottom is the one that carries the acceptance criterion 非法迁移一律
 * 拒绝. It states the machine independently of the production table - as a predicate over "where the
 * order is" and "what was attempted" - so editing {@code OrderManager.LEGAL} makes the two disagree
 * rather than making the test agree with itself. It also compares the book against the row on every one
 * of the thirty-six attempts, which is the cheapest place to catch the two drifting: for a book holding
 * one buy order, the row's filled quantity <em>is</em> the position. Everything above it is a named path
 * a reader would look for: what each report does to the row, what the row's numbers become, what a
 * refusal costs, and what a trade does to the money.
 *
 * <p>Four assertions here are about <b>order</b> rather than content, and all four are the kind of thing
 * that passes by accident if nobody writes it down. The row is written before the migration is announced,
 * which is checked by reading the store from inside the publisher. The row exists before the order is
 * handed over for sending, which is the same store read from inside the outbox - a crash the other way
 * round leaves an order on the exchange this process has no record of. The book is written before the
 * fill is published, read from inside the publisher the same way, because {@code CircuitBreaker.onFill}
 * takes this trade's result to be the change in {@code portfolio.realizedPnl()} and a book updated
 * afterwards makes every closing trade read as a zero. And a redelivered request neither moves the row
 * nor reaches the outbox, which is FR-EX-02's two halves. Two more break a store write on purpose,
 * because the order of the three writes on a trade is otherwise only a comment: it is observable exactly
 * when one of them fails.
 *
 * <p>The outbox here records rather than sends. What a hand-over does once it leaves the engine thread
 * is {@code OrderSenderTest}'s subject, and mixing the two would make a gateway failure look like a
 * state-machine failure.
 */
class OrderManagerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final String ID = "ma-cross-btc-1700000000000-1";
    /** The flip needs a second order: one order has one side, so 翻仓 is two of them. */
    private static final String SELL_ID = "ma-cross-btc-1700000000000-2";
    private static final String EXCHANGE_ID = "2639485123";
    private static final long T0 = 1_700_000_000_000L;

    /** Three contracts, so an order can fill in one piece, in three, or stop part way. */
    private static final BigDecimal QTY = new BigDecimal("3");
    private static final BigDecimal ONE = new BigDecimal("1");
    private static final BigDecimal FIVE = new BigDecimal("5");
    /** Four contracts, so one order can be built from a piece of three and a piece of one. */
    private static final BigDecimal FOUR = new BigDecimal("4");
    private static final BigDecimal PRICE = new BigDecimal("100");
    /** An eighth decimal set, so a weighted mean over four contracts does not terminate there. */
    private static final BigDecimal ODD_PRICE = new BigDecimal("100.00000001");
    private static final BigDecimal FEE = new BigDecimal("0.05");
    /** Big enough that no case here is limited by cash rather than by the thing it tests. */
    private static final BigDecimal EQUITY = new BigDecimal("10000");

    private record Harness(OrderManager oms, InMemoryOrderStore store, Portfolio portfolio,
                           List<Event> published, RecordingOutbox outbox) {
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
        Portfolio portfolio = new Portfolio(EQUITY);
        List<Event> published = new ArrayList<>();
        RecordingOutbox outbox = new RecordingOutbox();
        Harness harness = new Harness(new OrderManager(store, outbox, portfolio), store, portfolio,
                published, outbox);
        harness.oms().onEvent(request, published::add);
        return harness;
    }

    private static OrderRequestEvent request() {
        return request(ID, Side.BUY, QTY);
    }

    private static OrderRequestEvent request(String clientOrderId, Side side, BigDecimal qty) {
        return OrderRequestEvent.of(clientOrderId, BTC, side, OrderType.MARKET, qty, null, T0);
    }

    private OrderRecord row(Harness harness) {
        return harness.store().find(ID).orElseThrow();
    }

    private static List<OrderUpdateEvent> updates(List<Event> events) {
        return events.stream().filter(OrderUpdateEvent.class::isInstance)
                .map(OrderUpdateEvent.class::cast).toList();
    }

    private static List<FillEvent> fills(List<Event> events) {
        return events.stream().filter(FillEvent.class::isInstance)
                .map(FillEvent.class::cast).toList();
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
        // One at 100 and one at 200. Both quotients terminate, so the row's normalization to Money's
        // stored scale changes nothing here; the case where it does is its own test below.
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

    // ------------------------------------------------------------------ the money

    /**
     * 多次成交: three pieces of one buy order become one position, and its entry price is the same
     * number as the row's average fill price. The two are computed by different recurrences - the row
     * re-weights from its own cumulative quantity, the book from the position it holds - so this
     * assertion is what stops them drifting apart, which is the difference between an order audit trail
     * that agrees with the account and one that merely looks like it.
     */
    @Test
    void anOrderFilledInPiecesBuildsOnePositionAtAveragedEntry() {
        Harness harness = harness();

        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 1), harness.published()::add);
        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE,
                new BigDecimal("200"), FEE, T0 + 2), harness.published()::add);
        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE,
                new BigDecimal("300"), FEE, T0 + 3), harness.published()::add);

        Position position = harness.portfolio().position(BTC);
        assertThat(position.direction()).isEqualTo(Direction.LONG);
        assertThat(position.qty()).isEqualByComparingTo(QTY);
        assertThat(position.entryPrice()).isEqualByComparingTo("200");
        // Both quotients terminate here, so the two answers are the same number and only their scales
        // differ. The case where the value differs too is the next test.
        assertThat(row(harness).avgFillPrice()).isEqualByComparingTo(position.entryPrice());
        assertThat(row(harness).avgFillPrice()).isEqualTo(Money.of("200"));
        assertThat(row(harness).filledQty()).isEqualByComparingTo(position.qty());
        assertThat(row(harness).filledQty()).isEqualTo(Money.of(QTY));

        assertThat(harness.portfolio().feeTotal()).isEqualByComparingTo("0.15");
        assertThat(harness.portfolio().cash()).isEqualByComparingTo("9999.85");
        // Nothing marked this symbol, so the book falls back to the entry price and the position is
        // carried at cost: opening a position is not a profit and not a loss.
        assertThat(harness.portfolio().equity()).isEqualByComparingTo("9999.85");
        assertThat(alerts(harness.published())).isEmpty();
    }

    /**
     * The case the test above cannot reach: a weighted mean that does not terminate at the scale this
     * system stores money at. Three contracts at 100 and one at 100.00000001 average to
     * 400.00000001 / 4 = 100.0000000025, and {@code Money.divide} answers at 24 significant digits, so
     * without the row's normalization that ten-decimal number is what a TEXT column would hold.
     *
     * <p>The book keeps the long answer, because {@code Portfolio} normalizes its cash, its realized P&L
     * and its fees but not its entry price. So the row and the book genuinely differ here - by less than
     * half a unit in the eighth decimal, which is finer than any exchange filter and is the resolution
     * everything else in this system rounds to. Pinning the gap is what makes it a documented bound
     * rather than a divergence reconciliation eventually trips over; making the two bit-identical would
     * mean changing {@code Portfolio}'s entry price, which is P2 arithmetic every backtest number rests
     * on and is not this task's to move.
     */
    @Test
    void theStoredAverageIsRoundedToTheScaleThisSystemStoresMoneyAt() {
        Harness harness = harness(request(ID, Side.BUY, FOUR));
        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, QTY, PRICE, FEE, T0 + 1),
                harness.published()::add);
        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE, ODD_PRICE, FEE, T0 + 2),
                harness.published()::add);

        OrderRecord row = row(harness);
        assertThat(row.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(row.filledQty()).isEqualTo(Money.of(FOUR));
        assertThat(row.avgFillPrice()).isEqualTo(Money.of("100.00000000"));
        assertThat(row.avgFillPrice().scale()).isEqualTo(Money.SCALE);
        // The announcement carries the row's number, not the book's longer one - a consumer of the event
        // and a reader of the row have to be looking at the same thing.
        assertThat(updates(harness.published()).getLast().avgPrice()).isEqualTo(Money.of("100.00000000"));

        Position position = harness.portfolio().position(BTC);
        assertThat(position.qty()).isEqualByComparingTo(FOUR);
        assertThat(position.entryPrice()).isEqualByComparingTo("100.0000000025");
        assertThat(position.entryPrice().subtract(row.avgFillPrice()).abs())
                .isLessThan(new BigDecimal("0.000000005"));
    }

    @Test
    void theStoredFillCarriesTheSameNumbersTheBookWasGiven() {
        Harness harness = harness();
        harness.published().clear();

        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 1), harness.published()::add);

        FillEvent published = fills(harness.published()).getFirst();
        // One object stored and published, so the audit trail and the bus cannot describe two trades.
        assertThat(harness.store().fills(ID)).containsExactly(published);
        assertThat(published.clientOrderId()).isEqualTo(ID);
        assertThat(published.symbol()).isEqualTo(BTC);
        assertThat(published.side()).isEqualTo(Side.BUY);
        // The exchange's numbers at the canonical scale: the fills table is a TEXT column and holds
        // whatever scale it is given, and Money documents scale 8 for stored values. Comparing by value
        // alone would let the same amount be stored two ways in two rows of the same table.
        assertThat(published.price()).isEqualTo(Money.of(PRICE));
        assertThat(published.qty()).isEqualTo(Money.of(ONE));
        assertThat(published.fee()).isEqualTo(Money.of(FEE));
        assertThat(published.timestamp()).isEqualTo(T0 + 1);
    }

    /**
     * The report carries neither a symbol nor a side, so both come from the row - which is why a sell
     * order's execution has to reduce rather than add, and why a trade for an order this process never
     * held can never reach the book at all.
     */
    @Test
    void theSideOfATradeComesFromTheRowItBelongsTo() {
        Harness harness = harness(request(SELL_ID, Side.SELL, QTY));
        harness.published().clear();

        harness.oms().onEvent(OrderReportEvent.ofTrade(SELL_ID, EXCHANGE_ID, QTY, PRICE, FEE, T0 + 1),
                harness.published()::add);

        Position position = harness.portfolio().position(BTC);
        assertThat(position.direction()).isEqualTo(Direction.SHORT);
        assertThat(position.qty()).isEqualByComparingTo(QTY);
        assertThat(fills(harness.published()).getFirst().side()).isEqualTo(Side.SELL);
        // A short opened at cost is still a position carried at cost, not a loss.
        assertThat(harness.portfolio().realizedPnl()).isEqualByComparingTo("0");
    }

    /**
     * 翻仓: five sold against three held closes the long and opens a short, in one execution. The
     * realized figure is the whole old position and the entry price is this fill's, so the new short is
     * not carrying the long's history with it.
     */
    @Test
    void aTradeThatFlipsThePositionRealizesTheOldOneAndOpensTheNewOne() {
        Harness harness = harness();
        harness.oms().onEvent(Attempt.FULL_TRADE.report(T0 + 1), harness.published()::add);
        harness.oms().onEvent(request(SELL_ID, Side.SELL, FIVE), harness.published()::add);
        harness.published().clear();

        harness.oms().onEvent(OrderReportEvent.ofTrade(SELL_ID, EXCHANGE_ID, FIVE,
                new BigDecimal("200"), FEE, T0 + 2), harness.published()::add);

        Position position = harness.portfolio().position(BTC);
        assertThat(position.direction()).isEqualTo(Direction.SHORT);
        assertThat(position.qty()).isEqualByComparingTo("2");
        assertThat(position.entryPrice()).isEqualByComparingTo("200");
        assertThat(harness.portfolio().realizedPnl()).isEqualByComparingTo("300");
        assertThat(harness.portfolio().cash()).isEqualByComparingTo("10299.90");
        // The row that flipped is a different order from the one that opened, so its own average is its
        // own: five at two hundred, with no trace of the long that was closed.
        assertThat(harness.store().find(SELL_ID).orElseThrow().avgFillPrice()).isEqualByComparingTo("200");
        assertThat(harness.store().find(SELL_ID).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
        assertThat(harness.store().findOpen()).isEmpty();
    }

    /**
     * A missing commission is not a missing execution. Zero is used for both the stored fill and the
     * book, so the two agree with each other and differ only from the exchange - a difference
     * reconciliation can find, unlike a null in one place and a zero in the other.
     */
    @Test
    void aTradeWithoutACommissionStillFillsAndChargesNothing() {
        Harness harness = harness();
        harness.published().clear();

        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE, PRICE, null, T0 + 1),
                harness.published()::add);

        assertThat(row(harness).status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(fills(harness.published()).getFirst().fee()).isEqualTo(Money.zero());
        assertThat(harness.store().fills(ID).getFirst().fee()).isEqualTo(Money.zero());
        assertThat(harness.portfolio().feeTotal()).isEqualTo(Money.zero());
        assertThat(harness.portfolio().cash()).isEqualByComparingTo(EQUITY);
        assertThat(harness.portfolio().position(BTC).qty()).isEqualByComparingTo(ONE);
        assertThat(alerts(harness.published())).isEmpty();
    }

    /**
     * The OMS never invents market data. Marking at the fill price would be one line and would look like
     * an improvement - keeping equity fresh - but it would make equity depend on whether a trade arrived
     * rather than on the feed, and backtest marks from k-lines alone, so the two modes would value the
     * same position differently (FR-BT-06).
     */
    @Test
    void aFillDoesNotSetAMarkPrice() {
        Harness harness = harness();
        harness.portfolio().mark(BTC, PRICE);

        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, QTY,
                new BigDecimal("200"), FEE, T0 + 1), harness.published()::add);

        assertThat(harness.portfolio().markOf(BTC)).isEqualByComparingTo(PRICE);
        // Three bought at 200 and still marked at 100: the loss is visible, which is the point. Had the
        // fill set the mark, equity would read 9999.95 and the position would look like it cost nothing.
        assertThat(harness.portfolio().equity()).isEqualByComparingTo("9699.95");
    }

    @Test
    void theFillIsPublishedBeforeTheMigrationItCaused() {
        Harness harness = harness();
        harness.published().clear();

        harness.oms().onEvent(Attempt.PARTIAL_TRADE.report(T0 + 1), harness.published()::add);

        // Cause then consequence, which is the order a journal reader has to follow: the migration says
        // the order is PARTIALLY_FILLED because of the fill beside it, not the other way round.
        assertThat(harness.published()).hasSize(2);
        assertThat(harness.published().getFirst()).isInstanceOf(FillEvent.class);
        assertThat(harness.published().get(1)).isInstanceOf(OrderUpdateEvent.class);
        assertThat(updates(harness.published()).getFirst().filledQty())
                .isEqualTo(row(harness).filledQty());
    }

    // ------------------------------------------------------------------ order of operations

    @Test
    void theRowIsWrittenBeforeTheMigrationIsAnnounced() {
        InMemoryOrderStore store = new InMemoryOrderStore();
        List<Event> published = new ArrayList<>();
        List<OrderStatus> statusSeenByThePublisher = new ArrayList<>();
        OrderManager oms = new OrderManager(store, request -> true, new Portfolio(EQUITY));

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

    /**
     * 取舍 16's rule, asserted from the consumer's side rather than the producer's, because the consumer
     * is a different module: {@code CircuitBreaker.onFill} takes this trade's result to be the change in
     * {@code portfolio.realizedPnl()} since the last fill it saw, so a book written afterwards reports
     * every closing trade as a zero and the losing streak never breaks. Reading the position and the
     * realized total at the moment the event arrives is the only way to catch that from here.
     */
    @Test
    void theBookIsWrittenBeforeTheFillIsPublished() {
        InMemoryOrderStore store = new InMemoryOrderStore();
        Portfolio portfolio = new Portfolio(EQUITY);
        List<Event> published = new ArrayList<>();
        List<Position> positionWhenTheFillArrived = new ArrayList<>();
        List<BigDecimal> realizedWhenTheFillArrived = new ArrayList<>();
        OrderManager oms = new OrderManager(store, request -> true, portfolio);

        // A close, so realized P&L is not zero and a stale book would be visible in it rather than
        // hidden behind it: buy three, then sell three at a higher price.
        oms.onEvent(request(), published::add);
        oms.onEvent(Attempt.FULL_TRADE.report(T0 + 1), published::add);
        oms.onEvent(request(SELL_ID, Side.SELL, QTY), published::add);
        published.clear();

        EventPublisher publisher = event -> {
            published.add(event);
            if (event instanceof FillEvent) {
                positionWhenTheFillArrived.add(portfolio.position(BTC));
                realizedWhenTheFillArrived.add(portfolio.realizedPnl());
                // The fill row is durable state like the order row, so it is written before the event
                // too: a consumer that reacts to a fill by asking which fills this order has must get
                // this one back.
                assertThat(store.fills(SELL_ID)).hasSize(1);
            }
        };
        oms.onEvent(OrderReportEvent.ofTrade(SELL_ID, EXCHANGE_ID, QTY,
                new BigDecimal("120"), FEE, T0 + 2), publisher);

        assertThat(positionWhenTheFillArrived).hasSize(1);
        assertThat(positionWhenTheFillArrived.getFirst().direction()).isEqualTo(Direction.FLAT);
        assertThat(realizedWhenTheFillArrived).containsExactly(Money.of("60"));
        assertThat(portfolio.realizedPnl()).isEqualTo(Money.of("60"));
    }

    @Test
    void theRowExistsBeforeTheOrderIsHandedOverForSending() {
        InMemoryOrderStore store = new InMemoryOrderStore();
        List<Event> published = new ArrayList<>();
        List<OrderStatus> statusAtHandOver = new ArrayList<>();
        OrderManager oms = new OrderManager(store, request -> {
            statusAtHandOver.add(store.find(request.clientOrderId()).orElseThrow().status());
            return true;
        }, new Portfolio(EQUITY));

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

    /**
     * The write order is only observable when a write fails, so here one does. Whichever of the two
     * durable writes throws, the book is untouched, nothing is published, and the row is still open - so
     * reconciliation comes back to it. That last part is why the append-only fill goes first: with the
     * order row written first, a failure in {@code saveFill} would leave a row already saying FILLED, and
     * FILLED is terminal, so {@code findOpen()} would never return it and the divergence would be
     * permanent and silent.
     *
     * <p>The exception escapes rather than becoming an alert, and that is deliberate: a store that cannot
     * write is not a fact about the order, and reporting it on the order's channel would read as one.
     * {@code EventEngine.dispatch} catches it and logs it, which is the same treatment every handler's
     * failure gets.
     */
    @Test
    void aStoreThatCannotWriteTheFillRowLeavesEverythingAsItWas() {
        FailingOrderStore store = FailingOrderStore.onFillRow();
        Portfolio portfolio = new Portfolio(EQUITY);
        List<Event> published = new ArrayList<>();
        OrderManager oms = new OrderManager(store, request -> true, portfolio);
        oms.onEvent(request(), published::add);
        store.arm();
        published.clear();

        assertThatThrownBy(() -> oms.onEvent(Attempt.FULL_TRADE.report(T0 + 1), published::add))
                .isInstanceOf(IllegalStateException.class);

        assertThat(store.find(ID).orElseThrow().status()).isEqualTo(OrderStatus.NEW);
        assertThat(store.fills(ID)).isEmpty();
        assertThat(portfolio.position(BTC).isEmpty()).isTrue();
        assertThat(portfolio.cash()).isEqualByComparingTo(EQUITY);
        assertThat(published).isEmpty();
    }

    @Test
    void aStoreThatCannotWriteTheOrderRowStillLeavesTheBookUntouched() {
        FailingOrderStore store = FailingOrderStore.onOrderRow();
        Portfolio portfolio = new Portfolio(EQUITY);
        List<Event> published = new ArrayList<>();
        OrderManager oms = new OrderManager(store, request -> true, portfolio);
        oms.onEvent(request(), published::add);
        store.arm();
        published.clear();

        assertThatThrownBy(() -> oms.onEvent(Attempt.FULL_TRADE.report(T0 + 1), published::add))
                .isInstanceOf(IllegalStateException.class);

        // The residue is one orphan fill: history of a trade the exchange says happened, recorded whether
        // or not the row caught up. The row did not, and it is still open, so reconciliation will.
        assertThat(store.fills(ID)).hasSize(1);
        assertThat(store.find(ID).orElseThrow().status()).isEqualTo(OrderStatus.NEW);
        assertThat(store.findOpen()).hasSize(1);
        assertThat(portfolio.position(BTC).isEmpty()).isTrue();
        assertThat(portfolio.cash()).isEqualByComparingTo(EQUITY);
        assertThat(published).isEmpty();
    }

    /**
     * A store with one write broken, so the ordering can be observed instead of being taken on trust from
     * a comment. It starts healthy and has to be {@link #arm() armed} between the open and the trade
     * report: intake writes the order row too, and a store that failed there would leave no row for the
     * report to find, measuring the unknown-order path instead of a write failure. Reads always pass
     * through.
     */
    private static final class FailingOrderStore implements OrderStore {

        private final InMemoryOrderStore delegate = new InMemoryOrderStore();
        private final boolean failOnOrderRow;
        private final boolean failOnFillRow;
        private boolean armed;

        private FailingOrderStore(boolean failOnOrderRow, boolean failOnFillRow) {
            this.failOnOrderRow = failOnOrderRow;
            this.failOnFillRow = failOnFillRow;
        }

        static FailingOrderStore onOrderRow() {
            return new FailingOrderStore(true, false);
        }

        static FailingOrderStore onFillRow() {
            return new FailingOrderStore(false, true);
        }

        void arm() {
            armed = true;
        }

        @Override
        public void save(OrderRecord order) {
            if (armed && failOnOrderRow) {
                throw new IllegalStateException("cannot write the order row");
            }
            delegate.save(order);
        }

        @Override
        public void saveFill(FillEvent fill) {
            if (armed && failOnFillRow) {
                throw new IllegalStateException("cannot write the fill row");
            }
            delegate.saveFill(fill);
        }

        @Override
        public Optional<OrderRecord> find(String clientOrderId) {
            return delegate.find(clientOrderId);
        }

        @Override
        public List<OrderRecord> findOpen() {
            return delegate.findOpen();
        }

        @Override
        public List<FillEvent> fills(String clientOrderId) {
            return delegate.fills(clientOrderId);
        }
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

    /**
     * The refusal that protects the money. A duplicated trade report from a reconnecting stream arrives
     * against an order that is already FILLED, and accepting it would book the same execution twice: the
     * position would double, the realized figure would double, and every downstream consumer would be
     * told a trade happened that did not. Nothing is written - not the row, not the book, not the fill
     * history - and nothing is published about the order.
     */
    @Test
    void aDuplicatedTradeOnAFilledOrderMovesNeitherTheRowNorTheBook() {
        Harness harness = driveTo(OrderStatus.FILLED);
        OrderRecord filled = row(harness);
        Position position = harness.portfolio().position(BTC);
        BigDecimal cash = harness.portfolio().cash();
        BigDecimal realized = harness.portfolio().realizedPnl();
        int storedFills = harness.store().fills(ID).size();
        assertThat(storedFills).isEqualTo(1);
        harness.published().clear();

        harness.oms().onEvent(Attempt.FULL_TRADE.report(T0 + 9), harness.published()::add);

        assertThat(row(harness)).isEqualTo(filled);
        assertThat(harness.portfolio().position(BTC)).isEqualTo(position);
        assertThat(harness.portfolio().cash()).isEqualTo(cash);
        assertThat(harness.portfolio().realizedPnl()).isEqualTo(realized);
        assertThat(harness.store().fills(ID)).hasSize(storedFills);
        assertThat(fills(harness.published())).isEmpty();
        assertThat(updates(harness.published())).isEmpty();
        assertThat(alerts(harness.published())).hasSize(1);
        assertThat(alerts(harness.published()).getFirst().severity())
                .isEqualTo(RiskAlertEvent.Severity.CRITICAL);
    }

    /**
     * A report whose numbers cannot be interpreted is refused before anything is computed, which is the
     * only place it can be refused safely: {@code afterTrade} divides the weighted price by the
     * cumulative filled quantity, so a zero-quantity report against an order that has never traded is a
     * division by zero, and {@code Portfolio.applyFill} throws on a non-positive quantity - an exception
     * after the row was written would leave the row claiming an execution the book never saw. All four
     * shapes are checked because each reaches a different line.
     */
    @Test
    void aTradeWhoseNumbersCannotBeInterpretedIsRefusedBeforeAnythingIsWritten() {
        List<OrderReportEvent> malformed = List.of(
                OrderReportEvent.ofTrade(ID, EXCHANGE_ID, BigDecimal.ZERO, PRICE, FEE, T0 + 1),
                OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE.negate(), PRICE, FEE, T0 + 1),
                OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE, null, FEE, T0 + 1),
                OrderReportEvent.ofTrade(ID, EXCHANGE_ID, ONE, BigDecimal.ZERO, FEE, T0 + 1));

        for (OrderReportEvent report : malformed) {
            Harness harness = harness();
            harness.published().clear();

            harness.oms().onEvent(report, harness.published()::add);

            String where = "qty=" + report.lastQty() + " price=" + report.lastPrice();
            assertThat(row(harness).status()).as(where).isEqualTo(OrderStatus.NEW);
            assertThat(row(harness).filledQty()).as(where).isEqualTo(Money.zero());
            assertThat(row(harness).avgFillPrice()).as(where).isNull();
            assertThat(harness.portfolio().position(BTC).isEmpty()).as(where).isTrue();
            assertThat(harness.portfolio().cash()).as(where).isEqualByComparingTo(EQUITY);
            assertThat(harness.store().fills(ID)).as(where).isEmpty();
            assertThat(fills(harness.published())).as(where).isEmpty();
            assertThat(updates(harness.published())).as(where).isEmpty();
            assertThat(alerts(harness.published())).as(where).hasSize(1);
            RiskAlertEvent alert = alerts(harness.published()).getFirst();
            assertThat(alert.ruleId()).as(where).isEqualTo(OrderManager.RULE_MALFORMED_TRADE);
            // An untrustworthy input rather than an inconvenient moment: the exchange said something
            // happened and we cannot say what, which is the same class as a refused execution.
            assertThat(alert.severity()).as(where).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
            assertThat(alert.timestamp()).as(where).isEqualTo(T0 + 1);
            assertThat(alert.detail()).as(where).contains(ID)
                    .contains("nothing was written").contains("reconciliation");
        }
    }

    /**
     * The other half of the same guard: a malformed report against an order that has already traded must
     * leave what did trade alone. Before this existed a zero-quantity report was accepted here, because
     * PARTIALLY_FILLED &rarr; PARTIALLY_FILLED is a legal self-loop, and announced a migration that had
     * changed nothing.
     */
    @Test
    void aMalformedTradeAgainstAPartiallyFilledOrderLeavesWhatTradedAlone() {
        Harness harness = driveTo(OrderStatus.PARTIALLY_FILLED);
        OrderRecord traded = row(harness);
        Position position = harness.portfolio().position(BTC);
        harness.published().clear();

        harness.oms().onEvent(OrderReportEvent.ofTrade(ID, EXCHANGE_ID, BigDecimal.ZERO, PRICE, FEE,
                T0 + 9), harness.published()::add);

        assertThat(row(harness)).isEqualTo(traded);
        assertThat(harness.portfolio().position(BTC)).isEqualTo(position);
        assertThat(harness.store().fills(ID)).hasSize(1);
        assertThat(fills(harness.published())).isEmpty();
        assertThat(updates(harness.published())).isEmpty();
        assertThat(alerts(harness.published()).getFirst().ruleId())
                .isEqualTo(OrderManager.RULE_MALFORMED_TRADE);
    }

    /**
     * A trade report carries no symbol and no side - both come from the row - so an order this process
     * never held cannot be applied even in principle: there is nothing to say which instrument moved or
     * in which direction. Inventing them would book a position in a symbol nobody traded.
     */
    @Test
    void aTradeForAnOrderThisProcessNeverSentCannotReachTheBook() {
        Harness harness = harness();
        harness.published().clear();

        harness.oms().onEvent(OrderReportEvent.ofTrade("some-other-process-1", EXCHANGE_ID, ONE, PRICE,
                FEE, T0 + 1), harness.published()::add);

        assertThat(harness.store().find("some-other-process-1")).isEmpty();
        assertThat(harness.store().fills("some-other-process-1")).isEmpty();
        assertThat(harness.portfolio().positionsBySymbol()).isEmpty();
        assertThat(harness.portfolio().cash()).isEqualByComparingTo(EQUITY);
        assertThat(fills(harness.published())).isEmpty();
        assertThat(alerts(harness.published())).hasSize(1);
        assertThat(alerts(harness.published()).getFirst().ruleId())
                .isEqualTo(OrderManager.RULE_UNKNOWN_ORDER);
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
                Position before = harness.portfolio().position(BTC);
                int storedFills = harness.store().fills(ID).size();

                harness.oms().onEvent(attempt.report(T0 + 9), harness.published()::add);

                boolean shouldMove = moves(from, attempt.target);
                String where = from + " +" + attempt;
                if (shouldMove) {
                    OrderRecord moved = row(harness);
                    assertThat(moved.status()).as(where).isEqualTo(attempt.target);
                    assertThat(moved.updatedAt()).as(where).isEqualTo(T0 + 9);
                    assertThat(updates(harness.published())).as(where).hasSize(1);
                    assertThat(fills(harness.published())).as(where)
                            .hasSize(attempt.isTrade() ? 1 : 0);
                    assertThat(harness.store().fills(ID)).as(where)
                            .hasSize(storedFills + (attempt.isTrade() ? 1 : 0));
                    // The announcement describes the row, so it is compared to the row rather than to
                    // what the attempt asked for: an event quoting the order's quantity, or its
                    // creation time, is still a well-formed OrderUpdateEvent and reads like the truth.
                    OrderUpdateEvent update = updates(harness.published()).getFirst();
                    assertThat(update.clientOrderId()).as(where).isEqualTo(moved.clientOrderId());
                    assertThat(update.status()).as(where).isEqualTo(moved.status());
                    assertThat(update.filledQty()).as(where).isEqualTo(moved.filledQty());
                    assertThat(update.avgPrice()).as(where).isEqualTo(moved.avgFillPrice());
                    assertThat(update.timestamp()).as(where).isEqualTo(moved.updatedAt());
                    // And the row describes the book: one buy order in an otherwise empty book means the
                    // position <em>is</em> the filled quantity, so the two records of the same trade are
                    // compared on every accepted attempt rather than only on the ones written for it.
                    assertThat(harness.portfolio().position(BTC).signedQty()).as(where)
                            .isEqualByComparingTo(moved.filledQty());
                    assertThat(alerts(harness.published())).as(where).isEmpty();
                } else {
                    assertThat(row(harness).status()).as(where).isEqualTo(from);
                    assertThat(row(harness).updatedAt()).as(where).isNotEqualTo(T0 + 9);
                    assertThat(updates(harness.published())).as(where).isEmpty();
                    assertThat(fills(harness.published())).as(where).isEmpty();
                    // A refusal is not merely quiet, it is inert: no money moved and no history grew.
                    assertThat(harness.portfolio().position(BTC)).as(where).isEqualTo(before);
                    assertThat(harness.store().fills(ID)).as(where).hasSize(storedFills);
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
