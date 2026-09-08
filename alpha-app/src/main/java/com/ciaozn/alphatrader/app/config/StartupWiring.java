package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Boot sequence (after EnvValidator): register handlers -> start engine -> connect gateway.
 * Engine must run before the gateway connects so no market event is published into a dead loop.
 *
 * <p>Handler registration order IS dispatch order. The strategy engine is the only handler in
 * P2's online modes; the backtest assembly registers the simulated executor BEFORE it, so the
 * fills of a bar are applied before that bar's signals are produced (FR-BT-02).
 */
@Component
@Order(1)
public class StartupWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWiring.class);

    private final EventEngine engine;
    private final StrategyEngine strategyEngine;
    private final ObjectProvider<ExchangeGateway> gateways;
    private final AlphaProperties properties;

    public StartupWiring(EventEngine engine, StrategyEngine strategyEngine,
                         ObjectProvider<ExchangeGateway> gateways, AlphaProperties properties) {
        this.engine = engine;
        this.strategyEngine = strategyEngine;
        this.gateways = gateways;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        engine.registerHandler(strategyEngine);
        engine.start();

        gateways.ifAvailable(gateway -> {
            GatewayConfig config = properties.trading().enabled()
                    ? new GatewayConfig(properties.binanceTestnet(),
                            System.getenv("BINANCE_API_KEY"), System.getenv("BINANCE_API_SECRET"))
                    : GatewayConfig.marketDataOnly(properties.binanceTestnet());
            gateway.connect(config);
            log.info("Gateway connect initiated (testnet={}, trading={}) - supervisor owns reconnects",
                    properties.binanceTestnet(), properties.trading().enabled());
        });

        if (gateways.stream().findAny().isEmpty()) {
            log.info("No gateway in this profile (backtest feeder arrives in T214)");
        }
    }
}
