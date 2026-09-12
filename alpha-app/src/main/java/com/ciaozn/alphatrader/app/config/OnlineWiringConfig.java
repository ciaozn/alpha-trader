package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.app.recovery.StartupRecovery;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.execution.MarkPriceUpdater;
import com.ciaozn.alphatrader.execution.OrderManager;
import com.ciaozn.alphatrader.execution.OrderSender;
import com.ciaozn.alphatrader.execution.OrderStore;
import com.ciaozn.alphatrader.execution.Reconciler;
import com.ciaozn.alphatrader.execution.ReconciliationRunner;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.risk.OrderRule;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RecordStore;
import com.ciaozn.alphatrader.risk.RiskGate;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.SignalRecorder;
import com.ciaozn.alphatrader.risk.SignalRule;
import com.ciaozn.alphatrader.risk.SnapshotSampler;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembly for the two online modes (T319): the same components as a backtest, with the simulated
 * executor replaced by a real gateway and the clock replaced by the wall clock.
 *
 * <p><b>Every bean here is annotated {@code @Profile({"paper","live"})}.</b> That is the whole point
 * of the profile mechanism in this project: backtest and download must be able to run - and exit - in
 * a container that has no gateway, no order store and no reconciliation thread. A single unannotated
 * bean would silently give those profiles a half-built trading system.
 *
 * <p><b>The handler list is a bean, because registration order is dispatch order.</b>
 * {@link OnlineHandlers} is built once here and registered by {@link StartupWiring}; the alternative -
 * injecting {@code List<EventHandler>} and letting Spring order it - would make the sequence a
 * function of bean names, which is to say a function of nothing. The one constraint that matters is
 * stated in {@link #onlineHandlers}: the signal recorder runs before the gate, because the
 * interception row the gate writes joins to the signal row on event id.
 */
@Configuration
@Profile({"paper", "live"})
public class OnlineWiringConfig {

    private static final Logger log = LoggerFactory.getLogger(OnlineWiringConfig.class);

    @Bean
    DataSource dataSource(StoreProperties store) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(store.jdbcUrl());
        log.info("Business store: {}", store.jdbcUrl());
        return dataSource;
    }

    @Bean
    OrderStore orderStore(DataSource dataSource) {
        return new com.ciaozn.alphatrader.app.data.JdbcOrderStore(dataSource);
    }

    @Bean
    RecordStore recordStore(DataSource dataSource) {
        return new com.ciaozn.alphatrader.app.data.JdbcRecordStore(dataSource);
    }

    /**
     * Lazy on purpose: fetching rules is a network call, and a container test that supplies its own
     * (fixed) provider must not be forced through it. In production it is still created at startup,
     * because {@link RiskGate} - an eager singleton - depends on it.
     */
    @Bean
    @Lazy
    TradingRulesProvider tradingRulesProvider(AlphaProperties properties) {
        return OnlineTradingRules.fetch(properties);
    }

    @Bean
    PositionSizer positionSizer(AlphaProperties properties) {
        return new PositionSizer(new PositionSizer.Policy(
                properties.risk().sizing().effectiveTargetExposure()));
    }

    @Bean
    RiskPipeline riskPipeline(AlphaProperties properties, Portfolio portfolio) {
        return RiskPipelines.of(properties.risk(), portfolio);
    }

    @Bean
    SignalRecorder signalRecorder(RecordStore records) {
        return new SignalRecorder(records);
    }

    @Bean
    RiskGate riskGate(Portfolio portfolio, PositionSizer sizer, TradingRulesProvider rules,
                      RiskPipeline pipeline, Clock clock, RecordStore records) {
        return new RiskGate(portfolio, sizer, rules, pipeline, clock, records);
    }

    @Bean
    OrderSender orderSender(ExchangeGateway gateway, EventEngine engine, Clock clock) {
        // Started by StartupWiring, after the engine: an event published into a stopped engine is an
        // event nobody will ever dispatch.
        return new OrderSender(gateway, engine, clock);
    }

    @Bean
    OrderManager orderManager(OrderStore store, OrderSender sender, Portfolio portfolio) {
        return new OrderManager(store, sender, portfolio);
    }

    @Bean
    MarkPriceUpdater markPriceUpdater(Portfolio portfolio) {
        return new MarkPriceUpdater(portfolio);
    }

    @Bean
    SnapshotSampler snapshotSampler(Portfolio portfolio, RecordStore records, Clock clock) {
        return new SnapshotSampler(portfolio, records, clock);
    }

    @Bean
    Reconciler reconciler(OrderStore store, Portfolio portfolio, Clock clock, AlphaProperties properties) {
        return new Reconciler(store, portfolio, clock, Reconciler.DEFAULT_EQUITY_TOLERANCE,
                properties.reconciliation().autoCloseGhostPositions());
    }

    @Bean(destroyMethod = "close")
    ReconciliationRunner reconciliationRunner(ExchangeGateway gateway, Reconciler reconciler,
                                              EventEngine engine, OnlineProperties online) {
        return new ReconciliationRunner(gateway, reconciler, engine, online.reconcilePeriod());
    }

    /**
     * T403: verifies the event journal against the exchange-synced book at startup. Not an
     * {@code ApplicationRunner} - {@link StartupWiring} calls it at the one point in its sequence where
     * the exchange facts are applied and no market event can have been dispatched yet.
     */
    @Bean
    StartupRecovery startupRecovery(EventJournal journal, Portfolio portfolio, OrderStore orderStore,
                                    Clock clock, EventEngine engine) {
        return new StartupRecovery(journal, portfolio, orderStore, clock, engine);
    }

    /**
     * The dispatch sequence, assembled in one place so the order is readable and assertable.
     *
     * <p>Marks first: every rule reads equity, and equity is only meaningful once the book has a
     * price. Recorder before the gate (interception rows join to signal rows). Rules that observe
     * events - the circuit breaker counts fills - sit next, then the OMS, then the snapshot sampler,
     * and strategies last, exactly as in the backtest: a strategy that produced a signal for this bar
     * must not see that bar's fills first.
     */
    @Bean
    OnlineHandlers onlineHandlers(MarkPriceUpdater marks, SignalRecorder recorder, RiskGate gate,
                                  RiskPipeline pipeline, OrderManager oms, SnapshotSampler sampler,
                                  StrategyEngine strategies) {
        List<String> names = new ArrayList<>();
        List<EventHandler> handlers = new ArrayList<>();
        add(names, handlers, "MarkPriceUpdater", marks);
        add(names, handlers, "SignalRecorder", recorder);
        add(names, handlers, "RiskGate", gate);
        for (SignalRule rule : pipeline.signalRules()) {
            if (rule instanceof EventHandler handler) {
                add(names, handlers, rule.ruleId(), handler);
            }
        }
        for (OrderRule rule : pipeline.orderRules()) {
            if (rule instanceof EventHandler handler) {
                add(names, handlers, rule.ruleId(), handler);
            }
        }
        add(names, handlers, "OrderManager", oms);
        add(names, handlers, "SnapshotSampler", sampler);
        add(names, handlers, "StrategyEngine", strategies);
        return new OnlineHandlers(List.copyOf(names), List.copyOf(handlers));
    }

    private static void add(List<String> names, List<EventHandler> handlers, String name, EventHandler handler) {
        names.add(name);
        handlers.add(handler);
    }
}
