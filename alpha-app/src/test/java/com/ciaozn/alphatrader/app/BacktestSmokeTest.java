package com.ciaozn.alphatrader.app;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import com.ciaozn.alphatrader.app.config.BacktestWiring;
import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.Series;
import com.ciaozn.alphatrader.backtest.report.BacktestReport;
import com.ciaozn.alphatrader.backtest.report.PerformanceMetrics;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.strategy.config.StrategyRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CI backtest smoke test (T220, plan §5): one committed dataset, the shipped default
 * configuration, and assertions that a later change cannot quietly move.
 *
 * <p>Two different questions get two different kinds of assertion, because one number cannot answer
 * both. <em>Determinism</em> (SC-02) is answered by replaying the same input three times and
 * requiring the results to be bit-identical - that catches a {@code HashMap} iteration order, a
 * wall-clock value leaking into the report, or a race, none of which change the average outcome.
 * <em>Behaviour</em> is answered by pinning the measured performance: a change to the fill price,
 * the slippage model, the indicator windows or the position sizing moves {@code totalReturn}, and
 * whoever moves it has to look at this file and say why.
 *
 * <p>The dataset is committed rather than downloaded so the build stays hermetic; the range is a
 * fixed absolute window for the same reason - a rolling "last 100 days" fixture would silently
 * re-measure itself and every threshold below would drift with the market.
 * {@code BacktestSmokeFixtureTest} regenerates it and asserts that regenerating it changes nothing.
 *
 * <p>The measured baseline is a <em>loss</em>: -13.4% over the window with a 15.8% maximum
 * drawdown. A green smoke test therefore says the pipeline is unchanged, and nothing at all about
 * whether the shipped strategy is worth running - that question belongs to the operator reading the
 * report, not to CI.
 *
 * <p><b>What is deliberately not asserted here.</b> Annualized return. This window is 100 days, and
 * geometric extrapolation of a 100-day result to a year produces numbers that look like a bug and
 * are arithmetic - a 60-bar run of this same pipeline annualized to 530622%. A threshold on it
 * would either be so wide as to assert nothing or would fail on every legitimate change to the
 * window. It is reported, never thresholded; see {@code PerformanceAnalyzer}.
 *
 * <p>No container: {@code BacktestWiring} is a plain class, and {@code BacktestProfileApplicationTest}
 * already covers the profile-level acceptance. Going through the wiring rather than
 * {@code BacktestRunner} directly is the point - this is the path the packaged jar runs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BacktestSmokeTest {

    static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    static final String DATASET_DIR = "backtest-smoke";

    /** 2024-01-01T00:00:00Z to 2024-04-09T23:00:00Z, both inclusive on openTime: 2400 hourly bars. */
    static final long FROM = 1_704_067_200_000L;
    static final long TO = 1_712_703_600_000L;
    static final int BARS = 2400;

    /** SC-02 says three. Running them all once, here, is also what makes the pinning tests cheap. */
    private static final int RUNS = 3;

    private static final long HOUR = 3_600_000L;
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    /** The snapshot shipped in application-backtest.yml; see the comment there for its provenance. */
    private static final TradingRules RULES = new TradingRules(BTC,
            new BigDecimal("0.10"), new BigDecimal("0.0001"), new BigDecimal("50"));

    // Static because @BeforeAll runs before an instance @TempDir field would be injected.
    @TempDir
    static Path reports;

    private final List<Run> runs = new ArrayList<>();

    /** One replay: the report and the exact bytes written to disk for it. */
    private record Run(BacktestReport report, byte[] html) {
    }

    @BeforeAll
    void replayThreeTimes() throws IOException {
        for (int run = 1; run <= RUNS; run++) {
            Path directory = reports.resolve("run-" + run);
            // A fresh wiring per run, so the strategies are rebuilt rather than resumed: a Strategy
            // carries state across bars, and replaying into a warmed-up one is a different experiment.
            BacktestReport report = new BacktestWiring(properties(directory),
                    new StrategyRegistry()).backtest();
            List<Path> written;
            try (Stream<Path> files = Files.list(directory)) {
                written = files.sorted().toList();
            }
            // Exactly one artifact in a directory that did not exist before the run. The filename
            // itself is pinned by BacktestProfileApplicationTest; repeating the literal here would
            // be a third copy of a string that has to change in one step.
            assertThat(written).as("what run %d left in its report directory", run).hasSize(1);
            runs.add(new Run(report, Files.readAllBytes(written.get(0))));
        }
        PerformanceMetrics metrics = runs.get(0).report().metrics();
        System.out.printf("[smoke] bars=%d gaps=%d closed=%d open=%d fills=%d rejections=%d%n"
                        + "[smoke] equity %s -> %s  totalReturn=%s  maxDrawdown=%s  fees=%s  funding=%s%n"
                        + "[smoke] winRate=%s  sharpe=%s  annualized=%s  periods=%d%n",
                runs.get(0).report().replay().barsReplayed(), runs.get(0).report().replay().gaps().size(),
                metrics.closedTrades(), metrics.openTrades(), runs.get(0).report().fills().size(),
                runs.get(0).report().rejections().size(),
                metrics.startingEquity().toPlainString(), metrics.finalEquity().toPlainString(),
                metrics.totalReturn().toPlainString(), metrics.maxDrawdown().toPlainString(),
                metrics.totalFees().toPlainString(), runs.get(0).report().fundingTotal().toPlainString(),
                metrics.winRate(), metrics.sharpe(), metrics.annualizedReturn(), metrics.periods());
    }

    // ------------------------------------------------------------------ the committed dataset

    @Test
    void theDatasetIsTheExactWindowTheThresholdsWereMeasuredOn() {
        List<Kline> bars = new CsvKlineRepository(dataset()).load(BTC, Interval.H1, FROM, TO);

        assertThat(bars).hasSize(BARS);
        // One assertion that implies the count, the ordering, the endpoints and the absence of
        // holes: a bar at every hour of the window and no bar anywhere else.
        for (int index = 0; index < BARS; index++) {
            assertThat(bars.get(index).openTime())
                    .as("bar %d must sit on the hourly grid anchored at the window start", index)
                    .isEqualTo(FROM + index * HOUR);
        }
        assertThat(Instant.ofEpochMilli(FROM).atZone(ZoneOffset.UTC).toLocalDate().toString())
                .isEqualTo("2024-01-01");
        assertThat(Instant.ofEpochMilli(TO).atZone(ZoneOffset.UTC).toString())
                .isEqualTo("2024-04-09T23:00Z");
    }

    // ------------------------------------------------------------------ SC-02: determinism

    @Test
    void threeRunsOfTheSameInputAreBitIdentical() {
        assertThat(runs).hasSize(RUNS);
        Run first = runs.get(0);
        for (int index = 1; index < runs.size(); index++) {
            Run again = runs.get(index);
            // Every metric at once: PerformanceMetrics is a record of exact BigDecimals and
            // Optionals and holds no wall-clock value, so equals is a bit-for-bit comparison.
            assertThat(again.report().metrics()).as("run %d metrics", index + 1).isEqualTo(first.report().metrics());
            assertThat(again.report().curve()).as("run %d equity curve", index + 1).isEqualTo(first.report().curve());
            assertThat(again.report().trades()).as("run %d trades", index + 1).isEqualTo(first.report().trades());
            assertThat(again.report().fills()).as("run %d fills", index + 1).isEqualTo(first.report().fills());
            assertThat(again.report().funding()).as("run %d funding", index + 1).isEqualTo(first.report().funding());
            // The artifact an operator actually compares. Elapsed time is in the report but not in
            // the HTML, precisely so that this comparison can be exact.
            assertThat(again.html()).as("run %d report bytes", index + 1).isEqualTo(first.html());
        }
    }

    // ------------------------------------------------------------------ the pinned behaviour

    @Test
    void theMeasuredPerformanceIsWhereItWasWhenTheseNumbersWereWritten() {
        BacktestReport report = runs.get(0).report();
        PerformanceMetrics metrics = report.metrics();

        // A config echo rather than a computed result: the report normalises money to Money scale,
        // so the configured "10000" comes back as 10000.00000000 and isEqualTo would fail on scale.
        assertThat(metrics.startingEquity()).isEqualByComparingTo(EQUITY);

        // The strategy loses money on this window. That is not a defect and not a verdict on MA
        // cross: it is 100 days of hourly bars traded with taker fees, slippage and funding, and a
        // 26% win rate is what a trend follower looks like on a window that mostly chopped. What is
        // being pinned is that the pipeline still produces exactly these numbers - a change to the
        // fill price, the cost model, the indicator windows or the sizing moves them, and whoever
        // moves them has to come here and say why.
        assertThat(metrics.totalReturn()).isEqualTo(new BigDecimal("-0.13417588"));
        assertThat(metrics.maxDrawdown()).isEqualTo(new BigDecimal("0.15788502"));
        assertThat(metrics.finalEquity()).isEqualTo(new BigDecimal("8658.24122746"));
        assertThat(metrics.totalFees()).isEqualTo(new BigDecimal("338.75374834"));
        assertThat(report.fundingTotal()).isEqualTo(new BigDecimal("10.59291420"));

        // Counts are the most legible signal that behaviour moved: 123 fills close 122 round trips
        // and leave one position open at the end of the data, of which 32 were wins.
        assertThat(report.fills()).hasSize(123);
        assertThat(metrics.closedTrades()).isEqualTo(122);
        assertThat(metrics.openTrades()).isEqualTo(1);
        assertThat(metrics.wins()).isEqualTo(32);
        assertThat(metrics.losses()).isEqualTo(90);
    }

    @Test
    void theWholeWindowWasReplayedAndNothingWasRefused() {
        BacktestReport report = runs.get(0).report();

        assertThat(report.replay().barsReplayed()).isEqualTo(BARS);
        // A hole in the committed dataset would not fail loudly on its own: the run would simply
        // trade less and every pinned number would move by an amount that looks like a bug in the
        // strategy. Naming the gaps separately is what makes that case a one-line diagnosis.
        assertThat(report.replay().gaps()).isEmpty();
        assertThat(report.rejections()).isEmpty();
        assertThat(report.series()).containsExactly(new Series(BTC, Interval.H1));
    }

    // ------------------------------------------------------------------ fixture

    /**
     * The shipped defaults from {@code application.yml} - {@code ma-cross-btc} at 10/30 with
     * shorting allowed, and the shared {@code alpha.initial-cash}. A smoke test that invented its
     * own configuration would keep passing while the one an operator actually runs broke.
     */
    private static AlphaProperties properties(Path reportDir) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fast-period", "10");
        params.put("slow-period", "30");
        params.put("allow-short", "true");
        AlphaProperties.Backtest backtest = new AlphaProperties.Backtest(
                new AlphaProperties.Backtest.Data("csv", dataset(), null),
                Instant.ofEpochMilli(FROM), Instant.ofEpochMilli(TO), List.of(), null, null,
                List.of(new AlphaProperties.Backtest.RuleEntry(BTC.unified(), RULES.tickSize(),
                        RULES.stepSize(), RULES.minNotional())),
                reportDir, false, null);
        return new AlphaProperties("backtest", List.of(BTC.unified()), "1h", true,
                new AlphaProperties.Trading(false), Path.of("logs"), EQUITY,
                List.of(new AlphaProperties.StrategyEntry("ma-cross-btc", "ma-cross", true, null, null, params)),
                backtest);
    }

    /**
     * Resolved through the classloader rather than as {@code src/test/resources/...} because this is
     * the one test that must not depend on the working directory: a smoke test that fails with
     * "dataset not found" under one IDE's run configuration teaches people to ignore smoke tests.
     */
    private static Path dataset() {
        String resource = "/" + DATASET_DIR + "/" + BTC.unified() + "-" + Interval.H1.binanceCode() + ".csv";
        URL url = BacktestSmokeTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Committed smoke dataset is missing from the test "
                    + "classpath: " + resource + " - regenerate it with BacktestSmokeFixtureTest");
        }
        try {
            return Path.of(url.toURI()).getParent();
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot locate the smoke dataset as a directory: " + url, e);
        }
    }
}
