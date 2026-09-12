package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.EventPublisher;
import com.ciaozn.alphatrader.execution.InMemoryOrderStore;
import com.ciaozn.alphatrader.execution.MarkPriceUpdater;
import com.ciaozn.alphatrader.execution.OrderManager;
import com.ciaozn.alphatrader.execution.OrderOutbox;
import com.ciaozn.alphatrader.execution.OrderSender;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import com.ciaozn.alphatrader.risk.InMemoryRecordStore;
import com.ciaozn.alphatrader.risk.OrderLimitsRule;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.InterceptionRecord;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RecordStore;
import com.ciaozn.alphatrader.risk.RiskGate;
import com.ciaozn.alphatrader.risk.SignalRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec scenario 3, offline: a signal that breaks a limit never reaches the exchange, is announced,
 * and leaves a record naming the rule (T320 / FR-RK-01, RK-08).
 *
 * <p><b>Why this is an end-to-end test and not a gate unit test.</b> {@code RiskGateTest} already
 * proves the gate refuses. What it cannot prove is that the refusal is the last thing that happens:
 * if the OMS were registered ahead of the recorder, or if some other component re-published the
 * order, the gate's tests would stay green while real orders went out. The only assertion that
 * covers that is "the gateway was never called", and only an assembled pipeline can make it.
 *
 * <p>The gateway here is a counter, not a mock with expectations: we are not testing that it was
 * asked the right question, we are testing that it was not asked at all.
 */
class ScenarioThreeHardGateTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long NOW = 1_700_000_000_000L;

    private final InMemoryRecordStore records = new InMemoryRecordStore();
    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final VirtualClock clock = new VirtualClock(NOW);
    private final List<RiskAlertEvent> alerts = new CopyOnWriteArrayList<>();
    private final CountingGateway gateway = new CountingGateway();
    private EventEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void anOversizedSignalIsBlockedBeforeItReachesTheExchange() throws InterruptedException {
        // A deliberately tight order limit. The shipped configuration cannot produce this breach:
        // T302 pins the single-order limit at no less than twice the target exposure, so a signal the
        // sizer is willing to size is always inside the order limit. That invariant is why the gate
        // has to be provoked with a rule set of its own here - and why SC-04's matrix pins each rule
        // directly instead of trusting a live signal to break one.
        RiskPipeline tight = new RiskPipeline(List.of(),
                List.of(new OrderLimitsRule(new BigDecimal("0.05"), new BigDecimal("0.02"))));
        engine = assemble(tight);
        portfolio.mark(BTC, new BigDecimal("50000"));

        // One full-strength signal asks for 10% of equity, twice what this pipeline allows.
        SignalEvent signal = SignalEvent.of("ma-cross-1", BTC, Direction.LONG, 1.0,
                "golden cross", NOW);
        engine.publish(signal);
        engine.awaitQuiescence(signal.eventId());

        assertThat(gateway.placed).as("orders sent to the exchange").isEmpty();
        assertThat(alerts).anySatisfy(alert -> {
            assertThat(alert.ruleId()).isEqualTo(OrderLimitsRule.RULE_ID);
            assertThat(alert.detail()).contains("blocked");
        });

        List<InterceptionRecord> interceptions = records.interceptions(0L, Long.MAX_VALUE);
        assertThat(interceptions).hasSize(1);
        // The record must answer "which rule, and against what account" - a row with only a message
        // can be read but not audited.
        assertThat(interceptions.get(0).ruleId()).isEqualTo(OrderLimitsRule.RULE_ID);
        assertThat(interceptions.get(0).facts().equity()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(interceptions.get(0).facts().signal()).isEqualTo(signal);
    }

    @Test
    void aSignalWithinLimitsStillReachesTheExchange() throws InterruptedException {
        // The control: if this one were blocked too, the test above would be proving "nothing works"
        // rather than "the gate works".
        engine = assemble(shippedPipeline());
        portfolio.mark(BTC, new BigDecimal("50000"));

        SignalEvent signal = SignalEvent.of("ma-cross-1", BTC, Direction.LONG, 1.0, "golden cross", NOW);
        engine.publish(signal);
        engine.awaitQuiescence(signal.eventId());

        assertThat(gateway.placed).hasSize(1);
        assertThat(alerts).isEmpty();
        assertThat(records.interceptions(0L, Long.MAX_VALUE)).isEmpty();
    }

    private RiskPipeline shippedPipeline() {
        AlphaProperties properties = new AlphaProperties("paper", List.of(BTC.unified()), "1h", true,
                null, null, new BigDecimal("10000"), null, null, null, null, null);
        return RiskPipelines.of(properties.risk(), portfolio);
    }

    /** The online handler sequence, minus Spring: same order, same components. */
    private EventEngine assemble(RiskPipeline pipeline) {
        EventEngine local = new EventEngine(EventJournal.noop(), clock);
        OrderSender sender = new OrderSender(gateway, local, clock);
        local.registerHandler(new MarkPriceUpdater(portfolio));
        local.registerHandler(new SignalRecorder(records));
        local.registerHandler(new RiskGate(portfolio, new PositionSizer(PositionSizer.Policy.DEFAULT),
                rules(), pipeline, clock, records));
        local.registerHandler(new OrderManager(new InMemoryOrderStore(), outbox(sender), portfolio));
        local.registerHandler(alertCollector());
        local.start();
        sender.start();
        this.engine = local;
        return local;
    }

    /**
     * The sender is threaded, so an accepted order is placed on another thread; routing through an
     * outbox that records synchronously is what makes the "never called" assertion deterministic
     * without sleeping.
     */
    private OrderOutbox outbox(OrderSender sender) {
        return request -> {
            gateway.placed.add(request);
            return true;
        };
    }

    private EventHandler alertCollector() {
        return (Event event, EventPublisher publisher) -> {
            if (event instanceof RiskAlertEvent alert) {
                alerts.add(alert);
            }
        };
    }

    private static FixedTradingRulesProvider rules() {
        return FixedTradingRulesProvider.of(new TradingRules(BTC, new BigDecimal("0.10"),
                new BigDecimal("0.001"), new BigDecimal("5")));
    }

    /** Counts what the system asks of an exchange; answers nothing that could move the test along. */
    private static final class CountingGateway implements ExchangeGateway {
        final List<OrderRequestEvent> placed = new ArrayList<>();

        @Override
        public void connect(GatewayConfig config) {
        }

        @Override
        public void subscribeKline(Symbol symbol, Interval interval) {
        }

        @Override
        public OrderAck placeOrder(OrderRequestEvent request) {
            placed.add(request);
            return OrderAck.accepted(request.clientOrderId(), "1");
        }

        @Override
        public void cancelOrder(String clientOrderId) {
        }

        @Override
        public AccountSnapshot queryAccount() {
            return new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        @Override
        public List<Position> queryPositions() {
            return List.of();
        }

        @Override
        public List<OpenOrder> queryOpenOrders() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
