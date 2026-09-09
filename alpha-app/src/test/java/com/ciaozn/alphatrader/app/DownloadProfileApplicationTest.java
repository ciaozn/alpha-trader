package com.ciaozn.alphatrader.app;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import com.ciaozn.alphatrader.app.config.BacktestWiring;
import com.ciaozn.alphatrader.app.config.DownloadWiring;
import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance for T223 from the outside: starting the application with the download profile pulls
 * the configured history, writes it where the backtest will read it, and leaves nothing behind that
 * would hold the process open.
 *
 * <p>Only a test that boots the container can say that. The download profile's exit is not a setting,
 * it is the <em>absence</em> of beans: {@code EventEngine}'s loop thread is deliberately not a daemon
 * (the online modes have to stay up between bars), so one unconditional online bean anywhere in the
 * assembly is enough to make this profile hang after it has finished - exactly the failure T219 hit,
 * and exactly the one a plain unit test on {@code DownloadWiring} cannot see. The same is true of
 * OkHttp, whose client has no {@code close()} in 4.x and is released explicitly instead.
 *
 * <p>The exchange is a stub started before the context loads, because the runner executes during
 * startup: an {@code ApplicationRunner} has already finished by the time the first test method runs.
 */
@SpringBootTest(properties = {
        "alpha.download.from=2024-01-01T00:00:00Z",
        "alpha.download.to=2024-01-01T02:00:00Z",
        "alpha.backtest.data.source=csv",
        "alpha.backtest.data.csv-dir=target/download-acceptance/klines"})
@ActiveProfiles("download")
class DownloadProfileApplicationTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_704_067_200_000L;
    private static final long HOUR = 3_600_000L;
    private static final int BARS = 3;

    private static final Path STORE = Path.of("target", "download-acceptance", "klines");

    private static final StubKlineExchange EXCHANGE;

    static {
        try {
            EXCHANGE = new StubKlineExchange(StubKlineExchange.bars(BARS, T0, HOUR));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void pointTheDownloadAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("alpha.download.base-url", EXCHANGE::baseUrl);
    }

    @AfterAll
    static void stopTheStub() {
        // The stub's dispatch thread is not a daemon one, so leaving it running would hold the
        // surefire fork open after the tests had passed - the same trap this class exists to guard.
        EXCHANGE.close();
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DownloadWiring wiring;

    @Test
    void startingTheApplicationInTheDownloadProfileFillsTheStore() {
        CsvKlineRepository store = new CsvKlineRepository(STORE);

        assertThat(store.pathFor(BTC, Interval.H1)).exists();
        assertThat(store.load(BTC, Interval.H1, T0, T0 + (BARS - 1) * HOUR)).hasSize(BARS);
    }

    @Test
    void whatWasDownloadedIsWhatTheShippedConfigurationAskedFor() {
        // Nothing here overrides alpha.symbols or alpha.interval, so the series came out of
        // application.yml: BTCUSDT.PERP at 1h, translated to the name the exchange lists. That is the
        // default path an operator gets by setting only a range.
        assertThat(EXCHANGE.requests()).isNotEmpty();
        assertThat(EXCHANGE.requests().get(0))
                .containsEntry("symbol", "BTCUSDT")
                .containsEntry("interval", "1h");

        // Re-asking the same wiring is a second download, not a replay of the first, and the store
        // already has every bar in the range - so nothing new is written. This is the answer to "is
        // my store up to date?" without inspecting a directory.
        assertThat(wiring.download()).hasSize(1);
        assertThat(wiring.download().get(0).stored()).isZero();
    }

    @Test
    void theProfileCarriesNoneOfTheOnlineOrBacktestWiring() {
        // Each of these would either start a non-daemon thread or wait forever for something this
        // profile is not doing: market data no gateway will send, or a backtest over a range nobody
        // configured for it.
        assertThat(context.getBeanNamesForType(EventEngine.class)).isEmpty();
        assertThat(context.getBeanNamesForType(StrategyEngine.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ExchangeGateway.class)).isEmpty();
        assertThat(context.getBeanNamesForType(BacktestWiring.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DownloadWiring.class)).hasSize(1);
    }

    @Test
    void theDownloadProfileYmlBindsAndLeavesTheRangeToTheOperator() {
        AlphaProperties properties = context.getBean(AlphaProperties.class);

        // mode and trading.enabled come out of application-download.yml, so this is proof the
        // profile-specific file loaded rather than only the test's own properties.
        assertThat(properties.mode()).isEqualTo("download");
        assertThat(properties.trading().enabled()).isFalse();
        // An empty series list is the shipped default and means "alpha.symbols at alpha.interval".
        assertThat(properties.download().series()).isEmpty();
        // The range is set by the test properties above; the shipped file leaves it commented out,
        // because a range baked into a yml goes stale and a download with no range is refused.
        assertThat(properties.download().from()).isNotNull();
        assertThat(properties.download().to()).isNotNull();
        // The destination is the backtest's store, which is why it is configured under alpha.backtest
        // even in this profile.
        assertThat(properties.backtest().data().csvDir()).isEqualTo(STORE);
    }

    @Test
    void noThreadSurvivesTheRunThatCouldKeepTheProcessAlive() {
        Set<String> nonDaemon = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !thread.isDaemon())
                .map(Thread::getName)
                .collect(Collectors.toSet());

        assertThat(nonDaemon).noneMatch(name -> name.contains("event-engine"));
        assertThat(nonDaemon).noneMatch(name -> name.contains("OkHttp"));
    }
}
