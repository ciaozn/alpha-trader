package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.app.recovery.StartupRecovery;
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
 * <p><b>Recovery runs immediately after that first reconciliation pass, still before any market
 * event can be dispatched</b> (T403, FR-EN-05). Both are loop tasks, and the engine drains its task
 * queue before it polls the external queue, so the ordering here is enforced by the single-threaded
 * loop rather than by timing: exchange facts applied, journal checked against the corrected book, and
 * only then the first bar. It is deliberately not an {@code ApplicationRunner} - {@code EnvValidator}
 * already owns {@code @Order(0)}, and a runner that ran before this one would not yet have an engine
 * to verify on or a gateway to have synced from.
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
    private final StartupRecovery recovery;
    private final AlphaProperties properties;
    private final OnlineProperties online;

    public StartupWiring(EventEngine engine, OnlineHandlers handlers, ExchangeGateway gateway,
                         OrderSender orderSender, ReconciliationRunner reconciliation,
                         StartupRecovery recovery, AlphaProperties properties, OnlineProperties online) {
        this.engine = engine;
        this.handlers = handlers;
        this.gateway = gateway;
        this.orderSender = orderSender;
        this.reconciliation = reconciliation;
        this.recovery = recovery;
        this.properties = properties;
        this.online = online;
    }

    /**
     * Credentials are read per exchange here rather than in the gateway: an exchange adapter should
     * receive what it needs and not know where it came from, and OKX's passphrase is a third secret
     * with no Binance counterpart, so the difference belongs where the choice is made.
     */
    private static GatewayConfig credentials(OnlineProperties.GatewayType type, boolean testnet) {
        return switch (type) {
            case BINANCE -> new GatewayConfig(testnet,
                    System.getenv("BINANCE_API_KEY"), System.getenv("BINANCE_API_SECRET"));
            case OKX -> GatewayConfig.withPassphrase(testnet,
                    System.getenv("OKX_API_KEY"), System.getenv("OKX_API_SECRET"),
                    System.getenv("OKX_PASSPHRASE"));
        };
    }

    @Override
    public void run(ApplicationArguments args) {
        handlers.handlers().forEach(engine::registerHandler);
        log.info("Registered handlers in dispatch order: {}", handlers.names());

        engine.start();
        orderSender.start();

        GatewayConfig config = properties.trading().enabled()
                ? credentials(online.gateway(), properties.binanceTestnet())
                : GatewayConfig.marketDataOnly(properties.binanceTestnet());
        gateway.connect(config);
        log.info("Gateway connect initiated (exchange={}, testnet={}, trading={}) - supervisor owns reconnects",
                online.gateway(), properties.binanceTestnet(), properties.trading().enabled());

        if (properties.trading().enabled()) {
            reconciliation.start();
        } else {
            log.info("Trading disabled: reconciliation not started (no orders to reconcile)");
        }
        // Enqueued after reconciliation's own loop task, so it verifies the book the exchange sync
        // just corrected. Both run before the loop polls the external queue, hence before the first bar.
        recovery.start();
        engine.scheduleRepeating(SnapshotSampler.TIMER, online.snapshotPeriod());
        log.info("Snapshot sampler scheduled every {} s", online.snapshotPeriod().toSeconds());
    }
}
