package com.ciaozn.alphatrader.app;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import com.ciaozn.alphatrader.app.config.BacktestWiring;
import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.backtest.report.BacktestReport;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance criterion for T219, from the outside: starting the application with the backtest
 * profile replays the configured range, writes the report, and leaves nothing behind that would
 * hold the process open.
 *
 * <p>"Leaves nothing behind" is asserted structurally rather than by watching a process die, and
 * the structure is the whole mechanism: a JVM exits once {@code main} returns and only daemon
 * threads remain, so the one thing that could keep a backtest alive is the event engine's loop
 * thread - deliberately not a daemon, because the online modes have to stay up between bars. This
 * profile has no engine bean to start one, and the runner stops the engine it builds per run, so
 * asserting the absence of the bean, of the thread and of the online wiring is the same statement
 * as "the process exits", made where it can be checked.
 *
 * <p>The dataset is written before the context loads because the runner executes during startup:
 * an {@code ApplicationRunner} has already finished by the time the first test method runs.
 */
@SpringBootTest(properties = {
        "alpha.backtest.data.source=csv",
        "alpha.backtest.data.csv-dir=target/backtest-acceptance/klines",
        "alpha.backtest.from=2023-11-15T00:00:00Z",
        "alpha.backtest.to=2023-11-17T11:00:00Z",
        "alpha.backtest.report-dir=target/backtest-acceptance/reports",
        "alpha.initial-cash=10000"})
@ActiveProfiles("backtest")
class BacktestProfileApplicationTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_006_400_000L;
    private static final long HOUR = 3_600_000L;
    private static final int BARS = 60;

    private static final Path REPORT =
            Path.of("target", "backtest-acceptance", "reports", "backtest-report.html");

    static {
        // Thirty bars down from 100 and thirty back up, so the 10/30 cross the default
        // configuration asks for fires inside the window and the run trades rather than idles.
        int bottom = BARS / 2;
        List<Kline> bars = new ArrayList<>();
        BigDecimal open = new BigDecimal("101");
        for (int i = 0; i < BARS; i++) {
            BigDecimal close = i < bottom
                    ? BigDecimal.valueOf(100L - i)
                    : BigDecimal.valueOf(100L - bottom).add(BigDecimal.valueOf(i - bottom + 1L));
            bars.add(new Kline(T0 + i * HOUR, open, open.max(close).add(new BigDecimal("0.5")),
                    open.min(close).subtract(new BigDecimal("0.5")), close,
                    new BigDecimal("1000"), T0 + (i + 1) * HOUR - 1));
            open = close;
        }
        new CsvKlineRepository(Path.of("target", "backtest-acceptance", "klines"))
                .save(BTC, Interval.H1, bars);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BacktestWiring wiring;

    @Test
    void startingTheApplicationInTheBacktestProfileProducesTheReport() throws Exception {
        assertThat(Files.exists(REPORT)).isTrue();

        String html = Files.readString(REPORT, StandardCharsets.UTF_8);
        assertThat(html).contains("BTCUSDT.PERP").contains("10000");
    }

    @Test
    void theRunReplayedTheWholeRangeAndTradedInsideIt() throws Exception {
        // Asking the same wiring again is a second independent run, not a warm replay of the first:
        // the strategies are rebuilt every time, which is what makes SC-02's three runs comparable.
        BacktestReport report = wiring.backtest();

        assertThat(report.replay().barsReplayed()).isEqualTo(BARS);
        assertThat(report.replay().hasGaps()).isFalse();
        assertThat(report.fills()).isNotEmpty();
        assertThat(report.curve().startingEquity()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    void theProfileCarriesNoneOfTheOnlineWiring() {
        // Each of these would either start a non-daemon thread or wait forever for market data that
        // no gateway is going to send.
        assertThat(context.getBeanNamesForType(EventEngine.class)).isEmpty();
        assertThat(context.getBeanNamesForType(StrategyEngine.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ExchangeGateway.class)).isEmpty();
    }

    @Test
    void theYmlBlockBindsIncludingTheNestedPrecisionRules() {
        // The properties above override the range and the directories, so everything asserted here
        // came out of application-backtest.yml. A list of nested records that failed to bind would
        // arrive empty and refuse every run with a message about missing trading rules - a config
        // file that looks right and does nothing.
        AlphaProperties.Backtest backtest = context.getBean(AlphaProperties.class).backtest();

        assertThat(backtest.from()).isEqualTo(Instant.parse("2023-11-15T00:00:00Z"));
        assertThat(backtest.rules()).hasSize(1);
        assertThat(backtest.rules().get(0).symbol()).isEqualTo("BTCUSDT.PERP");
        assertThat(backtest.rules().get(0).tickSize()).isEqualByComparingTo("0.10");
        assertThat(backtest.rules().get(0).stepSize()).isEqualByComparingTo("0.0001");
        assertThat(backtest.rules().get(0).minNotional()).isEqualByComparingTo("50");
    }

    @Test
    void noThreadSurvivesTheRunThatCouldKeepTheProcessAlive() {
        Set<String> nonDaemon = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !thread.isDaemon())
                .map(Thread::getName)
                .collect(Collectors.toSet());

        // Whatever the test harness itself is running on, the application contributed no non-daemon
        // thread: the runner stops the engine it started, in a finally, on every path.
        assertThat(nonDaemon).noneMatch(name -> name.contains("event-engine"));
    }
}
