package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate is the only thing standing between a strategy's opinion and an order (FR-RK-01), so
 * these tests assert both directions: what comes out when a signal is sound, and that nothing
 * comes out - only an alert (FR-RK-08) - when it is not.
 */
class RiskGateTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final TradingRules BTC_RULES =
            new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("20"));
    private static final long T0 = 1_700_000_000_000L;

    private final List<Event> published = new ArrayList<>();

    private record Harness(Portfolio portfolio, VirtualClock clock, RiskGate gate) {
    }

    private Harness harness(String cash, TradingRules... rules) {
        Portfolio portfolio = new Portfolio(new BigDecimal(cash));
        VirtualClock clock = new VirtualClock(T0);
        RiskGate gate = new RiskGate(portfolio, new PositionSizer(PositionSizer.Policy.DEFAULT),
                FixedTradingRulesProvider.of(rules), clock);
        return new Harness(portfolio, clock, gate);
    }

    private static SignalEvent signal(String strategyId, Symbol symbol, Direction direction, double strength) {
        return SignalEvent.of(strategyId, symbol, direction, strength, "test", T0);
    }

    private static List<OrderRequestEvent> orders(List<Event> events) {
        return events.stream().filter(OrderRequestEvent.class::isInstance)
                .map(OrderRequestEvent.class::cast).toList();
    }

    private static List<RiskAlertEvent> alerts(List<Event> events) {
        return events.stream().filter(RiskAlertEvent.class::isInstance)
                .map(RiskAlertEvent.class::cast).toList();
    }

    @Test
    void turnsALongSignalIntoAMarketOrder() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).hasSize(1);
        OrderRequestEvent order = orders(published).getFirst();
        assertThat(order.symbol()).isEqualTo(BTC);
        assertThat(order.side()).isEqualTo(Side.BUY);
        assertThat(order.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.qty()).isEqualTo(new BigDecimal("30.000"));
        // a market order carries no price: the matcher fills at the next open, the OMS at the touch
        assertThat(order.price()).isNull();
        assertThat(order.timestamp()).isEqualTo(T0);
        assertThat(order.clientOrderId()).startsWith("ma-cross-btc-");
        assertThat(harness.gate().ordersPassed()).isEqualTo(1);
        assertThat(harness.gate().signalsBlocked()).isZero();
        assertThat(alerts(published)).isEmpty();
    }

    @Test
    void closesAHeldPositionOnAFlatSignal() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("30"), BigDecimal.ZERO);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), published::add);

        OrderRequestEvent order = orders(published).getFirst();
        assertThat(order.side()).isEqualTo(Side.SELL);
        assertThat(order.qty()).isEqualTo(new BigDecimal("30.000"));
    }

    @Test
    void givesEveryOrderAUniqueExchangeLegalId() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);
        harness.clock().advanceTo(T0 + 3_600_000L);
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        List<String> ids = orders(published).stream().map(OrderRequestEvent::clientOrderId).toList();
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> {
            assertThat(id).hasSizeLessThanOrEqualTo(36);
            assertThat(id).matches("[.A-Za-z0-9:/_-]{1,36}");
        });
    }

    @Test
    void twoIdenticalGatesProduceIdenticalOrders() {
        List<Event> firstPublished = new ArrayList<>();
        List<Event> secondPublished = new ArrayList<>();
        replay(harness("10000", BTC_RULES), firstPublished);
        replay(harness("10000", BTC_RULES), secondPublished);

        assertThat(orders(firstPublished)).hasSize(2);
        // eventIds come from a process-wide counter, everything the exchange sees must match (NFR-04)
        assertThat(describe(firstPublished)).isEqualTo(describe(secondPublished));
    }

    /** One open-then-close round trip, so both the quantity and the id sequence are compared. */
    private static void replay(Harness harness, List<Event> out) {
        harness.portfolio().mark(BTC, new BigDecimal("100"));
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), out::add);
        harness.portfolio().applyFill(BTC, Side.BUY, new BigDecimal("100"), new BigDecimal("30"), BigDecimal.ZERO);
        harness.clock().advanceTo(T0 + 3_600_000L);
        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), out::add);
    }

    private static List<String> describe(List<Event> events) {
        return orders(events).stream()
                .map(order -> order.clientOrderId() + "|" + order.symbol().unified() + "|" + order.side()
                        + "|" + order.orderType() + "|" + order.qty().toPlainString() + "|" + order.timestamp())
                .toList();
    }

    @Test
    void blocksAnOrderBelowTheExchangeMinimumAndAlertsInstead() {
        Harness harness = harness("50", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).isEmpty();
        assertThat(alerts(published)).hasSize(1);
        RiskAlertEvent alert = alerts(published).getFirst();
        assertThat(alert.ruleId()).isEqualTo(PositionSizer.RULE_MIN_NOTIONAL);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(alert.detail()).contains("ma-cross-btc").contains("BTCUSDT").contains("LONG");
        assertThat(harness.gate().signalsBlocked()).isEqualTo(1);
        assertThat(harness.gate().ordersPassed()).isZero();
    }

    @Test
    void missingTradingRulesAreAHardStop() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(ETH, new BigDecimal("3000"));

        harness.gate().onEvent(signal("ma-cross-eth", ETH, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).isEmpty();
        RiskAlertEvent alert = alerts(published).getFirst();
        assertThat(alert.ruleId()).isEqualTo(RiskGate.RULE_MISSING_TRADING_RULES);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(alert.detail()).contains("ETHUSDT");
    }

    @Test
    void anUnmarkedSymbolCannotBeSized() {
        Harness harness = harness("10000", BTC_RULES);

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.LONG, 1.0), published::add);

        assertThat(orders(published)).isEmpty();
        RiskAlertEvent alert = alerts(published).getFirst();
        assertThat(alert.ruleId()).isEqualTo(PositionSizer.RULE_NO_PRICE);
        assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
    }

    @Test
    void aFlatSignalWhileFlatPublishesNothing() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(signal("ma-cross-btc", BTC, Direction.FLAT, 1.0), published::add);

        assertThat(published).isEmpty();
        assertThat(harness.gate().ordersPassed()).isZero();
        assertThat(harness.gate().signalsBlocked()).isZero();
    }

    @Test
    void ignoresEverythingThatIsNotASignal() {
        Harness harness = harness("10000", BTC_RULES);
        harness.portfolio().mark(BTC, new BigDecimal("100"));

        harness.gate().onEvent(FillEvent.of("f1", BTC, Side.BUY, new BigDecimal("100"),
                new BigDecimal("1"), BigDecimal.ZERO, T0), published::add);
        harness.gate().onEvent(OrderRequestEvent.of("x", BTC, Side.BUY, OrderType.MARKET,
                BigDecimal.ONE, null, T0), published::add);
        harness.gate().onEvent(RiskAlertEvent.of("RK-01", RiskAlertEvent.Severity.INFO, "x", T0),
                published::add);

        assertThat(published).isEmpty();
    }
}
