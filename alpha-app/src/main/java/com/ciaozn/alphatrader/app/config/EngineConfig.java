package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.Clock;
import com.ciaozn.alphatrader.common.time.SystemClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.JsonlEventJournal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Core wiring for the online modes: clock, journal, engine and the one account book (DESIGN §10's
 * replaceable parts, of which gateways and executors live in profile-specific configs).
 *
 * <p>Backtest is absent on purpose. Its clock, engine and journal are per-run objects that
 * {@code BacktestRunner} builds itself: a singleton {@code VirtualClock} would have to start at the
 * first bar of whichever range happens to be configured, a singleton engine would outlive the
 * replay and hold the JVM open with its non-daemon loop thread, and a singleton journal would
 * accumulate every run into one file. The isomorphism FR-BT-06 asks for is that the components be
 * the same <em>classes</em> with the same behaviour, not the same instances - and the three that
 * differ between modes are exactly the three the runner constructs.
 */
@Configuration
@Profile({"paper", "live"})
public class EngineConfig {

    @Bean
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

    /**
     * The one account book every component reads and updates: strategies see positions through
     * it, the backtest matcher fills into it, and from P3 the OMS reconciles it against the
     * exchange (FR-EX-04). Shared arithmetic is what keeps backtest and live from drifting
     * apart (FR-BT-06).
     */
    @Bean
    Portfolio portfolio(AlphaProperties properties) {
        return new Portfolio(properties.initialCash());
    }
}
