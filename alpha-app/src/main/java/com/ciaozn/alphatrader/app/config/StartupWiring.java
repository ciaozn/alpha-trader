package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Boot sequence for the online modes (after EnvValidator): register handlers -> start engine ->
 * connect gateway. The engine must run before the gateway connects so no market event is published
 * into a dead loop, and these modes stay up: the engine's loop thread is not a daemon, which is
 * what keeps a non-web JVM alive between bars.
 *
 * <p>Handler registration order IS dispatch order. The strategy engine is the only handler here;
 * the backtest assembly registers the simulated executor BEFORE it, so the fills of a bar are
 * applied before that bar's signals are produced (FR-BT-02). The risk pipeline and the OMS join
 * this sequence in P3.
 *
 * <p>Backtest is excluded because it has neither a gateway nor a long-lived engine: it replays a
 * stated range and exits (see {@link BacktestWiring}).
 */
@Component
@Profile({"paper", "live"})
@Order(1)
public class StartupWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWiring.class);

    private final EventEngine engine;
    private final StrategyEngine strategyEngine;
    private final ExchangeGateway gateway;
    private final AlphaProperties properties;

    public StartupWiring(EventEngine engine, StrategyEngine strategyEngine,
                         ExchangeGateway gateway, AlphaProperties properties) {
        this.engine = engine;
        this.strategyEngine = strategyEngine;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        engine.registerHandler(strategyEngine);
        engine.start();

        GatewayConfig config = properties.trading().enabled()
                ? new GatewayConfig(properties.binanceTestnet(),
                        System.getenv("BINANCE_API_KEY"), System.getenv("BINANCE_API_SECRET"))
                : GatewayConfig.marketDataOnly(properties.binanceTestnet());
        gateway.connect(config);
        log.info("Gateway connect initiated (testnet={}, trading={}) - supervisor owns reconnects",
                properties.binanceTestnet(), properties.trading().enabled());
    }
}
