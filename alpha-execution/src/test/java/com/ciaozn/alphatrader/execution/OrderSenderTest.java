package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.OrderUpdateEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.EventPublisher;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T313: the send path, which is where 边界 6 actually gets decided (FR-EX-02).
 *
 * <p>The distinction under test is between two things that look identical from inside a {@code catch}:
 * the exchange took the order and refused it, and the exchange never answered. The first is a fact about
 * the order and moves the row to REJECTED. The second is the absence of a fact, and the only safe
 * response is to say so loudly and leave the row where it was, because the order may be resting on the
 * book. Recording "unknown" as "refused" is how one position becomes two: the strategy is told its order
 * was turned down, signals again, and the exchange fills both.
 *
 * <p>Most of this is exercised through {@code outcomeOf}, which is the whole of that decision with the
 * threading taken out, so each case is a straight-line call. Two tests put the thread back, because the
 * thread is the other half of the task: {@code placeOrder} is a blocking REST call and
 * {@code EventHandler} forbids network IO on the engine thread. The last two run the OMS and the sender
 * against one engine, which is the only place the loop is actually closed - a report published into a
 * void would satisfy every test above them.
 */
class OrderSenderTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final String ID = "ma-cross-btc-1700000000000-1";
    private static final String OTHER_ID = "ma-cross-btc-1700000000000-2";
    private static final String EXCHANGE_ID = "2639485123";
    private static final BigDecimal QTY = new BigDecimal("3");
    /** Only has to be large enough that the OMS's book never runs out mid-test. */
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    private static final long T0 = 1_700_000_000_000L;

    /** Generous, because these tests fail by timing out and a flaky build costs more than a slow one. */
    private static final long AWAIT_SECONDS = 15;

    /** The worker's name. Asserting on it is how a send is proved to have left the engine thread. */
    private static final String WORKER = "order-sender";

    private final VirtualClock clock = new VirtualClock(T0);
    /** Reversed on the way out: the sender stops before the engine it publishes into. */
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void shutDownEverything() throws Exception {
        // The engine's loop thread is non-daemon on purpose, so a test that leaks one leaks the fork:
        // surefire would sit waiting for a JVM that has nothing left to do.
        for (AutoCloseable closeable : toClose.reversed()) {
            closeable.close();
        }
        // Workers are named the same thing in every test, so one left behind would be counted by the
        // next. close() stops them; this waits for the stop to have taken effect.
        awaitTrue(() -> threadsNamed(WORKER).isEmpty(), "every order-sender thread to finish");
    }

    // ------------------------------------------------------------------ one attempt, one event

    @Test
    void anAcceptedPlacementBecomesASubmittedReportCarryingTheExchangeId() {
        OrderSender sender = idle(request -> OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID));

        OrderReportEvent report = (OrderReportEvent) sender.outcomeOf(request(ID));

        assertThat(report.clientOrderId()).isEqualTo(ID);
        // The handle reconciliation asks the exchange with (FR-EX-04). An ack that dropped it would
        // leave the order unfindable by anything except the id this process made up.
        assertThat(report.exchangeOrderId()).isEqualTo(EXCHANGE_ID);
        assertThat(report.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(report.message()).isNull();
        // An acknowledgment says an order exists, not that anything traded: inventing a fill here would
        // be the OMS's one way to move money it was never told about.
        assertThat(report.isTrade()).isFalse();
        assertThat(report.lastQty()).isNull();
        assertThat(report.timestamp()).isEqualTo(T0);
    }

    @Test
    void aRejectedPlacementIsAnAnswerSoItBecomesAReportRatherThanAnAlert() {
        OrderSender sender = idle(request ->
                OrderAck.rejected(request.clientOrderId(), "ReduceOnly Order is rejected"));

        Event outcome = sender.outcomeOf(request(ID));

        // Not an alert: the exchange took the request and said what it did with it, so the order is
        // definitively not on the book and the row can move.
        assertThat(outcome).isInstanceOf(OrderReportEvent.class);
        OrderReportEvent report = (OrderReportEvent) outcome;
        assertThat(report.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(report.message()).isEqualTo("ReduceOnly Order is rejected");
        assertThat(report.exchangeOrderId()).as("a rejected order was never given one").isNull();
        assertThat(report.isTrade()).isFalse();
    }

    @Test
    void anExchangeThatDidNotAnswerProducesAnAlertAndNoReportAtAll() {
        OrderSender sender = idle(request -> {
            throw new ExchangeUnreachableException("connect timed out after 5000ms");
        });

        Event outcome = sender.outcomeOf(request(ID));

        assertThat(outcome).isInstanceOf(RiskAlertEvent.class);
        RiskAlertEvent alert = (RiskAlertEvent) outcome;
        assertThat(alert.ruleId()).isEqualTo(OrderSender.RULE_UNREACHABLE);
        // Critical because the system now holds an order whose existence it cannot confirm, and only a
        // human or reconciliation can settle it.
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(alert.detail())
                .contains(ID)
                .contains("connect timed out after 5000ms")
                // The two facts an operator has to be able to read off one line: nothing is known, and
                // the order is still where it was. Naming any status here would be a claim.
                .contains("unknown")
                .contains(OrderStatus.NEW.name())
                .contains("reconciliation");
        assertThat(alert.timestamp()).isEqualTo(T0);
    }

    @Test
    void ourOwnSendPathFailingIsAlsoUnknownButUnderItsOwnRuleId() {
        OrderSender sender = idle(request -> {
            throw new IllegalStateException("no listen key");
        });

        RiskAlertEvent alert = (RiskAlertEvent) sender.outcomeOf(request(ID));

        assertThat(alert.ruleId()).isEqualTo(OrderSender.RULE_SEND_FAILED);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        // Same two facts as the unreachable case, because the situation is the same and only the blame
        // differs: nothing is known, and the row is left for reconciliation.
        assertThat(alert.detail()).contains(ID).contains("IllegalStateException")
                .contains("unknown").contains(OrderStatus.NEW.name()).contains("reconciliation");
    }

    @Test
    void aGatewayThatAnswersWithNothingStillProducesExactlyOneEvent() {
        OrderSender sender = idle(request -> null);

        RiskAlertEvent alert = (RiskAlertEvent) sender.outcomeOf(request(ID));

        // Reading the ack happens inside the same try as the call, so a broken answer degrades to
        // "unknown". Outside it, the NPE would reach the worker loop's catch-all: the attempt would
        // produce no event at all and the order would vanish behind one line of log.
        assertThat(alert.ruleId()).isEqualTo(OrderSender.RULE_SEND_FAILED);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(alert.detail()).contains(ID).contains("NullPointerException");
    }

    @Test
    void anOutcomeIsStampedWithWhenTheAttemptEndedNotWhenTheOrderWasRaised() {
        OrderSender answered = idle(request -> {
            // What a real gateway looks like: the answer arrives some time after the order was raised.
            clock.advanceTo(T0 + 400);
            return OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID);
        });
        OrderSender didNotAnswer = idle(request -> {
            clock.advanceTo(T0 + 900);
            throw new ExchangeUnreachableException("read timed out");
        });

        // The request's own timestamp is when the bar closed. Stamping either outcome with it would put
        // the exchange's answer in the past, and (timestamp, eventId) is how the journal reconstructs
        // ordering across processes - a backdated report would sort before the request that caused it.
        assertThat(((OrderReportEvent) answered.outcomeOf(request(ID))).timestamp()).isEqualTo(T0 + 400);
        assertThat(((RiskAlertEvent) didNotAnswer.outcomeOf(request(OTHER_ID))).timestamp())
                .isEqualTo(T0 + 900);
    }

    // ------------------------------------------------------------------ FR-EX-02 at the seam

    @Test
    void theSameClientOrderIdIsNeverHandedToTheExchangeTwice() {
        StubGateway gateway =
                new StubGateway(request -> OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID));
        OrderSender sender = idle(gateway);

        // Two separately constructed events carrying one id, so this tests the key and not identity: a
        // replayed journal rebuilds every event with a fresh eventId.
        assertThat(sender.submit(request(ID))).isTrue();
        assertThat(sender.submit(request(ID))).as("the second hand-over of one id").isFalse();
        assertThat(sender.submit(request(OTHER_ID))).as("a different order is a different id").isTrue();
    }

    @Test
    void closingASenderThatNeverStartedIsHarmless() {
        OrderSender sender = idle(request -> OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID));

        sender.close();
        // Destroy callbacks are not ordered against each other, and a context that failed half way up
        // closes beans that never started. Neither may throw on the way out.
        sender.close();

        assertThat(sender.submit(request(ID))).isFalse();
    }

    @Test
    void theWorkerIsADaemonThatStartsOnceAndIsGoneWhenTheSenderIsClosed() throws Exception {
        Running running = running(new StubGateway(
                request -> OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID)));

        // Daemon, unlike the engine's loop thread: that one is the application's reason to stay alive,
        // and a second non-daemon thread would be a second, independent way to hang the JVM once the
        // engine has let go.
        assertThat(threadsNamed(WORKER)).singleElement()
                .satisfies(worker -> assertThat(worker.isDaemon()).isTrue());

        running.sender().start();
        assertThat(threadsNamed(WORKER)).as("start() twice must not start a second worker").hasSize(1);

        running.sender().close();

        // The worker parks in a bounded poll and only leaves the loop when running is false, so a thread
        // still alive here is a close() that set closed without stopping anything.
        awaitTrue(() -> threadsNamed(WORKER).isEmpty(), "the worker thread to finish");
        assertThat(running.sender().submit(request(ID)))
                .as("the worker is gone, so a queued order would sit at NEW with nothing to explain it")
                .isFalse();
    }

    // ------------------------------------------------------------------ the thread

    @Test
    void aSubmittedOrderIsSentOnceAndNotOnTheEngineThread() throws Exception {
        StubGateway gateway =
                new StubGateway(request -> OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID));
        OrderRequestEvent order = request(ID);
        Running running = running(gateway);

        assertThat(running.sender().submit(order)).isTrue();
        List<Event> seen = running.collector().awaitEvents(1);

        // Awaited on the published outcome, which the loop publishes only after placeOrder returned and
        // the queue is empty - so this is the final tally, not a snapshot taken mid-flight. The engine's
        // own thread is "event-engine", so the name is the assertion: the blocking REST call did not
        // happen on the thread EventHandler forbids it on.
        assertThat(gateway.threads).containsExactly(WORKER);
        assertThat(gateway.placed).containsExactly(order);
        OrderReportEvent report = (OrderReportEvent) seen.getFirst();
        assertThat(report.clientOrderId()).isEqualTo(ID);
        assertThat(report.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(report.exchangeOrderId()).isEqualTo(EXCHANGE_ID);
    }

    @Test
    void theSenderKeepsSendingAfterAnAttemptThatProducedNoAnswer() throws Exception {
        StubGateway gateway = new StubGateway(request -> {
            if (request.clientOrderId().equals(ID)) {
                throw new ExchangeUnreachableException("connection reset");
            }
            return OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID);
        });
        Running running = running(gateway);

        running.sender().submit(request(ID));
        running.sender().submit(request(OTHER_ID));
        List<Event> seen = running.collector().awaitEvents(2);

        // One event per attempt, and there was a second attempt. A worker that died on the first outage
        // would stop every future order, and nothing else in the system would notice until
        // reconciliation found the rows still sitting at NEW.
        assertThat(seen.getFirst()).isInstanceOf(RiskAlertEvent.class);
        assertThat(((RiskAlertEvent) seen.getFirst()).ruleId()).isEqualTo(OrderSender.RULE_UNREACHABLE);
        assertThat(seen.get(1)).isInstanceOf(OrderReportEvent.class);
        assertThat(((OrderReportEvent) seen.get(1)).clientOrderId()).isEqualTo(OTHER_ID);
        assertThat(gateway.placed).hasSize(2);
    }

    // ------------------------------------------------------------------ the loop, closed

    /**
     * A request goes into the engine, the OMS opens the row and hands it over, the sender calls the
     * gateway on its own thread, and the answer comes back as an event that moves the same row. This is
     * the only place the two halves are wired to each other rather than to a recorder, so it is the only
     * place that would notice if the inbound type and the OMS's {@code switch} ever disagreed.
     */
    @Test
    void anOrderGoesOutAndTheExchangesAnswerMovesTheSameRow() throws Exception {
        StubGateway gateway =
                new StubGateway(request -> OrderAck.accepted(request.clientOrderId(), EXCHANGE_ID));
        SharedOrderStore store = new SharedOrderStore();
        Running running = running(gateway, store);

        running.engine().publish(request(ID));

        awaitTrue(() -> store.find(ID).map(OrderRecord::status).filter(OrderStatus.SUBMITTED::equals)
                .isPresent(), "the row to reach SUBMITTED");
        OrderRecord row = store.find(ID).orElseThrow();
        assertThat(row.exchangeOrderId()).isEqualTo(EXCHANGE_ID);
        assertThat(row.filledQty()).isEqualTo(Money.zero());
        assertThat(gateway.placed).hasSize(1);
        // The OMS's own announcement of each migration, and nothing else: a sender that published an
        // OrderUpdateEvent instead of a report would show up here as a third.
        assertThat(running.collector().seen.stream().filter(OrderUpdateEvent.class::isInstance))
                .hasSize(2);
        assertThat(running.collector().seen.stream().filter(RiskAlertEvent.class::isInstance)).isEmpty();
    }

    /** The acceptance criterion 传输异常不推进状态机, with the state machine actually attached. */
    @Test
    void anExchangeThatDidNotAnswerLeavesTheRowWhereItWas() throws Exception {
        StubGateway gateway = new StubGateway(request -> {
            throw new ExchangeUnreachableException("connect timed out");
        });
        SharedOrderStore store = new SharedOrderStore();
        Running running = running(gateway, store);

        running.engine().publish(request(ID));

        awaitTrue(() -> running.collector().seen.stream().anyMatch(RiskAlertEvent.class::isInstance),
                "the unreachable alert");
        OrderRecord row = store.find(ID).orElseThrow();
        // Still NEW, still no exchange id, still nothing filled - and the alert is the only trace. The
        // row is left for FR-EX-04's reconciliation, which is the component that can ask the exchange
        // what it actually holds.
        assertThat(row.status()).isEqualTo(OrderStatus.NEW);
        assertThat(row.exchangeOrderId()).isNull();
        assertThat(row.filledQty()).isEqualTo(Money.zero());
        assertThat(row.updatedAt()).as("nothing moved it").isEqualTo(row.createdAt());
        assertThat(running.collector().seen.stream().filter(OrderUpdateEvent.class::isInstance))
                .as("the open, and only the open").hasSize(1);
        RiskAlertEvent alert = running.collector().seen.stream()
                .filter(RiskAlertEvent.class::isInstance).map(RiskAlertEvent.class::cast).findFirst()
                .orElseThrow();
        assertThat(alert.ruleId()).isEqualTo(OrderSender.RULE_UNREACHABLE);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
    }

    // ------------------------------------------------------------------ harness

    private static OrderRequestEvent request(String clientOrderId) {
        return OrderRequestEvent.of(clientOrderId, BTC, Side.BUY, OrderType.MARKET, QTY, null, T0);
    }

    /** A sender with an engine that is never started: {@code outcomeOf} does not publish. */
    private OrderSender idle(StubGateway gateway) {
        EventEngine engine = new EventEngine(EventJournal.noop(), clock);
        toClose.add(engine);
        return new OrderSender(gateway, engine, clock);
    }

    private OrderSender idle(Function<OrderRequestEvent, OrderAck> placement) {
        return idle(new StubGateway(placement));
    }

    private record Running(OrderSender sender, EventEngine engine, Collector collector,
                           Portfolio portfolio) {
    }

    private Running running(StubGateway gateway) {
        return running(gateway, null);
    }

    /**
     * A started engine with a started sender publishing into it. When {@code store} is given the OMS is
     * registered too, and the sender is its outbox, so the loop is closed end to end. The book comes with
     * it because the OMS writes it on a trade report (取舍 16); with no OMS there is nothing to write one.
     */
    private Running running(StubGateway gateway, OrderStore store) {
        Collector collector = new Collector();
        EventEngine engine = new EventEngine(EventJournal.noop(), clock);
        OrderSender sender = new OrderSender(gateway, engine, clock);
        Portfolio portfolio = null;
        if (store != null) {
            portfolio = new Portfolio(EQUITY);
            engine.registerHandler(new OrderManager(store, sender, portfolio));
        }
        engine.registerHandler(collector);
        engine.start();
        toClose.add(engine);
        sender.start();
        toClose.add(sender);
        return new Running(sender, engine, collector, portfolio);
    }

    /** Records what the engine dispatched. Read from the test thread, written on the engine's. */
    private static final class Collector implements EventHandler {

        private final List<Event> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onEvent(Event event, EventPublisher publisher) {
            seen.add(event);
        }

        List<Event> awaitEvents(int count) throws InterruptedException {
            awaitTrue(() -> seen.size() >= count, "the engine to dispatch " + count + " event(s)");
            return List.copyOf(seen);
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                fail("timed out after " + AWAIT_SECONDS + "s waiting for " + what);
            }
            Thread.sleep(5L);
        }
    }

    /** Alive threads only, so an empty answer means the worker has finished rather than merely stopped. */
    private static List<Thread> threadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals(name)).toList();
    }

    /**
     * One method of the gateway matters here, so the rest throw rather than returning empty defaults: a
     * send path that wandered into reconciliation's queries would then fail instead of quietly seeing an
     * exchange that holds nothing.
     */
    private static final class StubGateway implements ExchangeGateway {

        private final Function<OrderRequestEvent, OrderAck> placement;
        /** Attempts, not answers: a placement that threw still reached the exchange as far as we know. */
        private final List<OrderRequestEvent> placed = new CopyOnWriteArrayList<>();
        private final List<String> threads = new CopyOnWriteArrayList<>();

        StubGateway(Function<OrderRequestEvent, OrderAck> placement) {
            this.placement = placement;
        }

        @Override
        public OrderAck placeOrder(OrderRequestEvent request) {
            threads.add(Thread.currentThread().getName());
            placed.add(request);
            return placement.apply(request);
        }

        @Override
        public void connect(GatewayConfig config) {
            throw unused();
        }

        @Override
        public void subscribeKline(Symbol symbol, Interval interval) {
            throw unused();
        }

        @Override
        public void cancelOrder(String clientOrderId) {
            throw unused();
        }

        @Override
        public AccountSnapshot queryAccount() {
            throw unused();
        }

        @Override
        public List<Position> queryPositions() {
            throw unused();
        }

        @Override
        public List<OpenOrder> queryOpenOrders() {
            throw unused();
        }

        @Override
        public void close() {
            throw unused();
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not part of the send path");
        }
    }

    /**
     * {@code InMemoryOrderStore} is engine-thread-confined by contract, and the two end-to-end tests read
     * it from the test thread while the engine writes it. The contract says an implementation serving
     * reads from another thread owns that synchronization, so the lock lives here rather than in the
     * store: promising it there would make every mode pay for one test's convenience.
     */
    private static final class SharedOrderStore implements OrderStore {

        private final OrderStore delegate = new InMemoryOrderStore();

        @Override
        public synchronized void save(OrderRecord order) {
            delegate.save(order);
        }

        @Override
        public synchronized void saveFill(FillEvent fill) {
            delegate.saveFill(fill);
        }

        @Override
        public synchronized Optional<OrderRecord> find(String clientOrderId) {
            return delegate.find(clientOrderId);
        }

        @Override
        public synchronized List<OrderRecord> findOpen() {
            return delegate.findOpen();
        }

        @Override
        public synchronized List<FillEvent> fills(String clientOrderId) {
            return delegate.fills(clientOrderId);
        }
    }
}
