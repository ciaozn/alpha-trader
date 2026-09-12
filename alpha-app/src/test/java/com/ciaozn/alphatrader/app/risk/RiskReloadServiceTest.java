package com.ciaozn.alphatrader.app.risk;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.risk.InMemoryRecordStore;
import com.ciaozn.alphatrader.risk.PortfolioRule;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RiskGate;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.RiskRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T404: the operator-facing reload, offline. The gate's own swap is pinned in alpha-risk's
 * {@code RiskGateTest}; this covers what the endpoint adds - the second confirmation, the audit
 * record, and the fact that the new limits are in force on the next signal without a restart.
 *
 * <p>The engine is real and running, because the service hands the swap to it: a test that stubbed
 * {@code runOnLoop} away would not be testing the ordering that makes the swap safe. The reload is
 * flushed by publishing a probe event and waiting for the loop to go quiet, so nothing here sleeps.
 */
class RiskReloadServiceTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final VirtualClock clock = new VirtualClock(T0);
    private final List<Event> published = new CopyOnWriteArrayList<>();

    private EventEngine engine;
    private RiskGate gate;
    private RiskReloadService service;

    @BeforeEach
    void setUp() {
        portfolio.mark(BTC, new BigDecimal("100"));
        engine = new EventEngine(EventJournal.noop(), clock);
        gate = new RiskGate(portfolio, new PositionSizer(PositionSizer.Policy.DEFAULT),
                FixedTradingRulesProvider.of(new TradingRules(BTC, new BigDecimal("0.10"),
                        new BigDecimal("0.001"), new BigDecimal("5"))),
                RiskPipeline.empty(), clock, new InMemoryRecordStore());
        engine.registerHandler(gate);
        engine.registerHandler((event, publisher) -> published.add(event));
        engine.start();
        service = new RiskReloadService(gate, portfolio, engine, clock);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }

    /**
     * A self-consistent block that happens to be tighter than the shipped one. The single-order cap
     * cannot bind under any legal {@code alpha.risk} (取舍 17 ties it to twice the exposure), so the
     * behaviour change is driven through the portfolio cap, which can.
     */
    private static AlphaProperties.Risk tightRisk() {
        return new AlphaProperties.Risk(
                AlphaProperties.Risk.Account.DEFAULTS,
                AlphaProperties.Risk.Order.DEFAULTS,
                new AlphaProperties.Risk.Portfolio(true, new BigDecimal("0.05"), new BigDecimal("0.05")),
                AlphaProperties.Risk.Breaker.DEFAULTS,
                AlphaProperties.Risk.Frequency.DEFAULTS,
                AlphaProperties.Risk.Sizing.DEFAULTS);
    }

    /** Applies the reload and waits for the loop to drain it, without sleeping. */
    private RiskReloadResult reloadAndAwait(AlphaProperties.Risk risk, boolean confirm)
            throws InterruptedException {
        RiskReloadResult result = service.reload(risk, confirm);
        TimerEvent probe = TimerEvent.of("reload-probe", clock.nowMillis());
        engine.publish(probe);
        engine.awaitQuiescence(probe.eventId());
        return result;
    }

    private void driveSignal() throws InterruptedException {
        // Strength 1.0 asks for 10% of equity = 1000 notional, which the tight portfolio cap refuses.
        SignalEvent signal = SignalEvent.of("ma-cross-btc", BTC, Direction.LONG, 1.0, "test", T0);
        engine.publish(signal);
        engine.awaitQuiescence(signal.eventId());
    }

    @Test
    void aReloadWithoutConfirmationIsRefusedAndThePreviousPipelineIsStillInForce() throws Exception {
        RiskPipeline before = gate.pipeline();

        RiskReloadResult result = reloadAndAwait(tightRisk(), false);

        assertThat(result.reloaded()).isFalse();
        assertThat(result.detail()).contains("confirm=true");
        assertThat(gate.pipeline()).isSameAs(before);
        assertThat(planAlerts()).isEmpty();
        // And the old rules still decide: the signal passes exactly as it did before the attempt.
        driveSignal();
        assertThat(orders()).hasSize(1);
    }

    @Test
    void aConfirmedReloadSwapsThePipelineAndWritesAnAuditRecord() throws Exception {
        RiskReloadResult result = reloadAndAwait(tightRisk(), true);

        assertThat(result.reloaded()).isTrue();
        assertThat(result.signalRules()).containsExactly("RK-02-account", "RK-05-breaker", "RK-06-frequency");
        assertThat(result.orderRules()).containsExactly("RK-03-order", "RK-04-portfolio");
        assertThat(gate.pipeline().orderRules()).extracting(RiskRule::ruleId)
                .containsExactly("RK-03-order", "RK-04-portfolio");
        // The audit is an INFO alert, not an incident: a deliberate change, findable afterwards.
        assertThat(planAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.INFO);
            assertThat(alert.detail()).contains("reloaded").contains("RK-04-portfolio");
        });

        // The point of FR-RK-09: the very next signal, with no restart, is decided by the new rules.
        driveSignal();
        assertThat(orders()).isEmpty();
        assertThat(alerts()).anySatisfy(alert ->
                assertThat(alert.ruleId()).isEqualTo(PortfolioRule.RULE_ID));
    }

    @Test
    void aStoppedEngineRefusesTheReloadRatherThanQueueingItForever() {
        EventEngine stopped = new EventEngine(EventJournal.noop(), clock);
        RiskReloadService onStopped = new RiskReloadService(gate, portfolio, stopped, clock);

        RiskReloadResult result = onStopped.reload(tightRisk(), true);

        assertThat(result.reloaded()).isFalse();
        assertThat(result.detail()).contains("not running");
    }

    private List<OrderRequestEvent> orders() {
        return published.stream().filter(OrderRequestEvent.class::isInstance)
                .map(OrderRequestEvent.class::cast).toList();
    }

    private List<RiskAlertEvent> alerts() {
        return published.stream().filter(RiskAlertEvent.class::isInstance)
                .map(RiskAlertEvent.class::cast).toList();
    }

    /** Only the audit alerts, so the gate's own block alerts do not have to be filtered at each call. */
    private List<RiskAlertEvent> planAlerts() {
        return alerts().stream().filter(alert -> alert.ruleId().equals(RiskReloadService.RULE_RELOADED)).toList();
    }
}
