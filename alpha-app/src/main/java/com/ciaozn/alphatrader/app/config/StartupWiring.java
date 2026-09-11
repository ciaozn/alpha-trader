package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.execution.OrderSender;
import com.ciaozn.alphatrader.execution.ReconciliationRunner;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.risk.SnapshotSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Boot sequence for the online modes (after EnvValidator): register handlers -> start engine ->
 * start the senders -> connect the gateway.
 *
 * <p>Handler registration order IS dispatch order, and it comes from {@link OnlineHandlers} rather
 * than from this class deciding: the sequence is a property of the assembly, and only one class
 * should know it.
 *
 * <p><b>The engine starts before anything can publish into it</b>, and the gateway connects last: a
 * market event arriving before the strategies are registered is an event no handler will see, and the
 * gateway is the only component that produces them on its own thread.
 *
 * <p><b>Reconciliation runs once at startup, before the first bar can produce a signal</b>
 * (spec edge case 7): a process restarting with a database full of open orders has to find out
 * immediately whether those orders still exist, not one period later.
 *
 * <p>These modes stay up: the engine's loop thread is not a daemon, which is what keeps a non-web JVM
 * alive between bars.
 */
@Component
@Profile({"paper", "live"})
@Order(1)
public class StartupWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWiring.class);

    private final EventEngine engine;
    private final OnlineHandlers handlers;
    private final ExchangeGateway gateway;
    private final OrderSender orderSender;
    private final ReconciliationRunner reconciliation;
    private final AlphaProperties properties;
    private final OnlineProperties online;

    public StartupWiring(EventEngine engine, OnlineHandlers handlers, ExchangeGateway gateway,
                         OrderSender orderSender, ReconciliationRunner reconciliation,
                         AlphaProperties properties, OnlineProperties online) {
        this.engine = engine;
        this.handlers = handlers;
        this.gateway = gateway;
        this.orderSender = orderSender;
        this.reconciliation = reconciliation;
        this.properties = properties;
        this.online = online;
    }

    @Override
    public void run(ApplicationArguments args) {
        handlers.handlers().forEach(engine::registerHandler);
        log.info("Registered handlers in dispatch order: {}", handlers.names());

        engine.start();
        orderSender.start();

        GatewayConfig config = properties.trading().enabled()
                ? new GatewayConfig(properties.binanceTestnet(),
                        System.getenv("BINANCE_API_KEY"), System.getenv("BINANCE_API_SECRET"))
                : GatewayConfig.marketDataOnly(properties.binanceTestnet());
        gateway.connect(config);
        log.info("Gateway connect initiated (testnet={}, trading={}) - supervisor owns reconnects",
                properties.binanceTestnet(), properties.trading().enabled());

        if (properties.trading().enabled()) {
            reconciliation.start();
        } else {
            log.info("Trading disabled: reconciliation not started (no orders to reconcile)");
        }
        engine.scheduleRepeating(SnapshotSampler.TIMER, online.snapshotPeriod());
        log.info("Snapshot sampler scheduled every {} s", online.snapshotPeriod().toSeconds());
    }
}
