package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.OrderStatus;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcilerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long NOW = 1_700_000_000_000L;

    private final InMemoryOrderStore orders = new InMemoryOrderStore();
    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final VirtualClock clock = new VirtualClock(NOW);
    private final Reconciler reconciler = new Reconciler(orders, portfolio, clock);

    private static AccountSnapshot account(BigDecimal equity) {
        return new AccountSnapshot(equity, equity, new BigDecimal("500"));
    }

    private static Reconciler.ExchangeState state(List<OpenOrder> openOrders, List<Position> positions,
                                                  BigDecimal equity) {
        return new Reconciler.ExchangeState(openOrders, positions, account(equity));
    }

    private void openOrder(String id) {
        orders.save(OrderRecord.ofNew(com.ciaozn.alphatrader.common.event.OrderRequestEvent.of(id, BTC,
                Side.BUY, OrderType.MARKET, new BigDecimal("0.1"), null, NOW), NOW));
    }

    private static List<RiskAlertEvent> alerts(List<Event> events) {
        return events.stream().filter(RiskAlertEvent.class::isInstance).map(RiskAlertEvent.class::cast).toList();
    }

    private static List<OrderReportEvent> reports(List<Event> events) {
        return events.stream().filter(OrderReportEvent.class::isInstance).map(OrderReportEvent.class::cast).toList();
    }

    @Test
    void anOrderThatIsNoLongerRestingIsCancelledAndAnnounced() {
        openOrder("ma-1-1");
        // Local says 0.1 long; exchange says flat and nothing is resting.
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.1"), BigDecimal.ZERO);

        List<Event> events = reconciler.reconcile(state(List.of(), List.of(), portfolio.equity()));

        assertThat(reports(events)).hasSize(1);
        assertThat(reports(events).get(0).status()).isEqualTo(OrderStatus.CANCELED);
        assertThat(reports(events).get(0).clientOrderId()).isEqualTo("ma-1-1");
        assertThat(alerts(events)).anySatisfy(alert -> {
            assertThat(alert.ruleId()).isEqualTo(Reconciler.RULE_MISSING_ORDER);
            assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        });
    }

    @Test
    void aPositionDisagreementIsCorrectedTowardsTheExchange() {
        portfolio.mark(BTC, new BigDecimal("50000"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.500"), BigDecimal.ZERO);
        List<Position> theirs = List.of(new Position(BTC, Direction.LONG, new BigDecimal("0.300"),
                new BigDecimal("50000"), BigDecimal.ZERO));

        List<Event> events = reconciler.reconcile(state(List.of(), theirs, portfolio.equity()));

        assertThat(portfolio.position(BTC).signedQty()).isEqualByComparingTo(new BigDecimal("0.300"));
        assertThat(alerts(events)).anySatisfy(alert ->
                assertThat(alert.ruleId()).isEqualTo(Reconciler.RULE_POSITION_MISMATCH));
    }

    @Test
    void aPositionTheExchangeClosedIsRemovedFromTheBook() {
        portfolio.mark(BTC, new BigDecimal("50000"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.500"), BigDecimal.ZERO);

        reconciler.reconcile(state(List.of(), List.of(), portfolio.equity()));

        assertThat(portfolio.openPositions()).isEmpty();
        assertThat(portfolio.position(BTC).isEmpty()).isTrue();
    }

    @Test
    void aGhostPositionIsReportedAndLeftAlone() {
        // The exchange holds something we have no record of: closing it is a P4 decision, so this
        // pass must only make noise - and must not fabricate a local position to match it.
        List<Position> theirs = List.of(new Position(BTC, Direction.SHORT, new BigDecimal("1.250"),
                new BigDecimal("50000"), BigDecimal.ZERO));

        List<Event> events = reconciler.reconcile(state(List.of(), theirs, portfolio.equity()));

        assertThat(alerts(events)).anySatisfy(alert -> {
            assertThat(alert.ruleId()).isEqualTo(Reconciler.RULE_GHOST_POSITION);
            assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
            assertThat(alert.detail()).contains("left open");
        });
        assertThat(portfolio.openPositions()).isEmpty();
    }

    @Test
    void anUnknownRestingOrderIsReportedButNotAdopted() {
        // Adopting it would invent parameters we never chose and give the next pass a false row.
        OpenOrder unknown = new OpenOrder("someone-else", BTC, Side.SELL, new BigDecimal("2"),
                new BigDecimal("49000"), OrderStatus.SUBMITTED);

        List<Event> events = reconciler.reconcile(state(List.of(unknown), List.of(), portfolio.equity()));

        assertThat(alerts(events)).anySatisfy(alert -> {
            assertThat(alert.ruleId()).isEqualTo(Reconciler.RULE_UNKNOWN_ORDER);
            assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        });
        assertThat(orders.find("someone-else")).isEmpty();
    }

    @Test
    void agreementProducesNothing() {
        portfolio.mark(BTC, new BigDecimal("50000"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.500"), BigDecimal.ZERO);
        openOrder("ma-1-2");
        OpenOrder resting = new OpenOrder("ma-1-2", BTC, Side.BUY, new BigDecimal("0.5"),
                new BigDecimal("0"), OrderStatus.SUBMITTED);
        List<Position> theirs = List.of(new Position(BTC, Direction.LONG, new BigDecimal("0.500"),
                new BigDecimal("50000"), BigDecimal.ZERO));

        assertThat(reconciler.reconcile(state(List.of(resting), theirs, portfolio.equity()))).isEmpty();
    }

    @Test
    void anEquityDifferenceBeyondToleranceIsAWarning() {
        portfolio.mark(BTC, new BigDecimal("50000"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.500"), BigDecimal.ZERO);
        BigDecimal reported = portfolio.equity().multiply(new BigDecimal("0.90")); // 10% below ours

        List<Event> events = reconciler.reconcile(state(List.of(), List.of(), reported));

        assertThat(alerts(events)).anySatisfy(alert -> {
            assertThat(alert.ruleId()).isEqualTo(Reconciler.RULE_EQUITY_MISMATCH);
            assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        });
    }
}
