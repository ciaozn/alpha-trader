package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.strategy.EchoStrategy;
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
 */
@Component
@Order(1)
public class StartupWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupWiring.class);

    private final EventEngine engine;
    private final EchoStrategy echoStrategy;
    private final ObjectProvider<ExchangeGateway> gateways;
    private final AlphaProperties properties;

    public StartupWiring(EventEngine engine, EchoStrategy echoStrategy,
                         ObjectProvider<ExchangeGateway> gateways, AlphaProperties properties) {
        this.engine = engine;
        this.echoStrategy = echoStrategy;
        this.gateways = gateways;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        engine.registerHandler(echoStrategy);
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
            log.info("No gateway in this profile (backtest feeder arrives in P2)");
        }
    }
}
