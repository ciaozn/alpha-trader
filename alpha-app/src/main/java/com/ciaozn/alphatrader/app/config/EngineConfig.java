package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.JsonlEventJournal;
import com.ciaozn.alphatrader.strategy.EchoStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Core wiring: the three replaceable parts of the isomorphism (DESIGN §10):
 * Clock, market data source, executor. This class owns Clock + journal + engine;
 * gateways/executors live in profile-specific configs.
 */
@Configuration
public class EngineConfig {

    /** Backtest: time only moves when the feeder advances it (deterministic, NFR-04). */
    @Bean
    @Profile("backtest")
    Clock virtualClock() {
        return new VirtualClock(0L);
    }

    @Bean
    @Profile({"paper", "live"})
    Clock systemClock() {
        return new SystemClock();
    }

    @Bean(destroyMethod = "close")
    EventJournal eventJournal(AlphaProperties properties) {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return new JsonlEventJournal(properties.journalDir().resolve("events-" + date + ".jsonl"));
    }

    @Bean(destroyMethod = "stop")
    EventEngine eventEngine(EventJournal journal, Clock clock) {
        return new EventEngine(journal, clock);
    }

    /** P1 placeholder proving the data path; replaced by the Strategy SPI assembly in P2. */
    @Bean
    EchoStrategy echoStrategy() {
        return new EchoStrategy();
    }
}
