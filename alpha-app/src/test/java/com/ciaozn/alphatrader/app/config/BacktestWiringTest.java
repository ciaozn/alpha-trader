package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.app.data.JdbcKlineRepository;
import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.Series;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor;
import com.ciaozn.alphatrader.backtest.report.BacktestReport;
import com.ciaozn.alphatrader.backtest.report.HtmlReportRenderer;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.strategy.config.StrategyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@code alpha.backtest.*} knob, from configuration to a number in the report (T219).
 *
 * <p>The subject here is translation, not trading - that the chain runs at all is T218's. So each
 * test moves one knob and reads the one value that knob owns. Where a knob's effect cannot be
 * observed without racing the engine, it is left to the component that implements it rather than
 * asserted here on luck: {@code quiescence-timeout} only matters when a round fails to close, and
 * whether a one-nanosecond timeout bites depends on which thread wins, so testing it here would be
 * a flaky test pretending to be coverage.
 *
 * <p>No container: the wiring is a plain class with two dependencies, and booting Spring to test a
 * record-to-record mapping would hide the mapping behind a context. The profile-level acceptance -
 * the process comes up, writes a report, and leaves nothing behind that would keep the JVM alive -
 * is {@code BacktestProfileApplicationTest}.
 */
class BacktestWiringTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_006_400_000L;
    private static final long HOUR = 3_600_000L;
    private static final int BARS = 24;
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    private static final TradingRules BTC_RULES = new TradingRules(BTC,
            new BigDecimal("0.10"), new BigDecimal("0.0001"), new BigDecimal("50"));

    @TempDir
    Path directory;

    // ------------------------------------------------------------------ the range and the book

    @Test
    void theConfiguredRangeDecidesHowManyBarsAreReplayed() throws IOException {
        assertThat(run(fixture()).replay().barsReplayed()).isEqualTo(BARS);

        Fixture fiveShorter = fixture();
        fiveShorter.to = Instant.ofEpochMilli(T0 + (BARS - 6) * HOUR);
        // Both ends are inclusive on openTime, so cutting the range five bars short replays 19.
        assertThat(run(fiveShorter).replay().barsReplayed()).isEqualTo(BARS - 5);
    }

    @Test
    void theStartingBookIsTheSharedKnobNotABacktestPrivateOne() throws IOException {
        assertThat(run(fixture()).curve().startingEquity()).isEqualByComparingTo(EQUITY);

        Fixture richer = fixture();
        richer.initialCash = new BigDecimal("25000");
        assertThat(run(richer).curve().startingEquity()).isEqualByComparingTo(new BigDecimal("25000"));
    }

    // ------------------------------------------------------------------ what gets replayed

    @Test
    void theSeriesAreDerivedFromWhatTheEnabledStrategiesListenTo() throws IOException {
        assertThat(run(fixture()).series()).containsExactly(new Series(BTC, Interval.H1));
    }

    @Test
    void anExplicitSeriesListReplacesTheDerivedOne() throws IOException {
        store().save(ETH, Interval.H1, bars(BARS));
        Fixture both = fixture();
        both.series = List.of(new AlphaProperties.Backtest.SeriesEntry("ETHUSDT.PERP", "1h"),
                new AlphaProperties.Backtest.SeriesEntry("BTCUSDT.PERP", "1h"));

        BacktestReport report = run(both);

        // Configuration order, not alphabetical: ETH was listed first.
        assertThat(report.series()).containsExactly(new Series(ETH, Interval.H1),
                new Series(BTC, Interval.H1));
        assertThat(report.replay().barsReplayed()).isEqualTo(2 * BARS);
    }

    // ------------------------------------------------------------------ the replaceable data source

    @Test
    void theDataSourceIsAConfigKnobAndNothingDownstreamCanTell() throws IOException {
        String fromCsv = HtmlReportRenderer.render(run(fixture()));

        Fixture fromDatabase = fixture();
        fromDatabase.source = "db";
        fromDatabase.jdbcUrl = "jdbc:sqlite:" + directory.resolve("klines.db");
        new JdbcKlineRepository(dataSource(fromDatabase.jdbcUrl)).save(BTC, Interval.H1, bars(BARS));

        // FR-BT-06: swapping the store must not reach the feeder, the strategies or the matcher.
        assertThat(HtmlReportRenderer.render(run(fromDatabase))).isEqualTo(fromCsv);
    }

    @Test
    void aDatabaseSourceNeedsASqliteUrl() {
        Fixture noUrl = fixture();
        noUrl.source = "db";
        assertThatThrownBy(() -> run(noUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha.backtest.data.jdbc-url must be set");

        Fixture mysql = fixture();
        mysql.source = "db";
        mysql.jdbcUrl = "jdbc:mysql://localhost:3306/alpha";
        // Refused here rather than inside the SQLite driver, which would fail on a URL it does not
        // understand with a message about SQLite. This module carries no other driver.
        assertThatThrownBy(() -> run(mysql))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a SQLite URL");
    }

    @Test
    void anUnknownDataSourceIsRefused() {
        Fixture parquet = fixture();
        parquet.source = "parquet";
        assertThatThrownBy(() -> run(parquet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha.backtest.data.source must be 'csv' or 'db'");
    }

    // ------------------------------------------------------------------ costs, sizing and rules

    @Test
    void aPartialCostOverrideKeepsTheDefaultsItDoesNotMention() throws IOException {
        Fixture noFee = fixture();
        noFee.cost = new AlphaProperties.Backtest.Cost(BigDecimal.ZERO, null, null, null);

        SimulatedExecutor.CostModel costs = run(noFee).costModel();
        SimulatedExecutor.CostModel defaults = SimulatedExecutor.CostModel.DEFAULT;

        assertThat(costs.takerFeeRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(costs.fixedSlippageBps()).isEqualByComparingTo(defaults.fixedSlippageBps());
        assertThat(costs.amplitudeFactor()).isEqualByComparingTo(defaults.amplitudeFactor());
        assertThat(costs.fundingRatePerInterval())
                .isEqualByComparingTo(defaults.fundingRatePerInterval());
    }

    @Test
    void theExposureKnobChangesTheSizeOfTheOrderAndNothingElseAboutIt() throws IOException {
        SimulatedExecutor.SimulatedFill normal = firstFill(run(fixture()));

        Fixture doubled = fixture();
        doubled.targetExposure = new BigDecimal("0.60");
        // Raising the exposure without the cap is refused at binding time - a flip is one order of
        // 2 x exposure, so the shipped 20% cap would block every one of them (取舍 17).
        doubled.maxOrderNotionalFraction = new BigDecimal("1.20");
        // And the portfolio level has to stand aside as well: it is wired into the shipped
        // configuration since T308, and its 30% single-symbol cap is tighter than a 60% exposure.
        // Which cap binds is asserted separately, in the test below.
        doubled.maxPortfolioNotionalFraction = new BigDecimal("1.20");
        SimulatedExecutor.SimulatedFill bigger = firstFill(run(doubled));

        // Same signal, same bar, same price - only the quantity moved. Comparing the whole fill
        // rather than the quantity alone is what makes this a statement about exposure and not
        // about two runs that happened to differ.
        assertThat(bigger.fillBarOpenTime()).isEqualTo(normal.fillBarOpenTime());
        assertThat(bigger.fillPrice()).isEqualByComparingTo(normal.fillPrice());
        assertThat(bigger.qty()).isGreaterThan(normal.qty());

        // And in the other direction the knob reaches the sizer too: this sizes below the exchange
        // minimum, so nothing at all is sent rather than a dirty order.
        Fixture tiny = fixture();
        tiny.targetExposure = new BigDecimal("0.001");
        assertThat(run(tiny).fills()).isEmpty();
    }

    @Test
    void theShippedPortfolioCapStopsAnOversizedOrderInARealRun() throws IOException {
        Fixture oversized = fixture();
        oversized.targetExposure = new BigDecimal("0.60");
        // The order level stands aside, so the check that binds is the portfolio one: a 60% exposure
        // is inside a 120% order cap but twice the shipped 30% single-symbol cap.
        oversized.maxOrderNotionalFraction = new BigDecimal("1.20");
        assertThat(run(oversized).fills()).isEmpty();

        // Same fixture with the portfolio caps lifted as well, and it trades - so the emptiness above
        // is the cap doing its job in a real run, not a strategy that stopped firing. The report
        // carries executor rejections but not risk blocks, which is why the attribution is made by
        // changing one knob rather than by reading a rule id off the run.
        oversized.maxPortfolioNotionalFraction = new BigDecimal("1.20");
        assertThat(run(oversized).fills()).isNotEmpty();
    }

    @Test
    void theConfiguredTradingRulesDecideWhatIsTradableAtAll() throws IOException {
        assertThat(run(fixture()).fills()).isNotEmpty();

        Fixture impossible = fixture();
        // A one-billion-USDT minimum: the gate blocks every order before the exchange is asked,
        // which is the configured rule doing its job rather than a broken run.
        impossible.rules = List.of(new AlphaProperties.Backtest.RuleEntry("BTCUSDT.PERP",
                new BigDecimal("0.10"), new BigDecimal("0.0001"), new BigDecimal("1000000000")));
        assertThat(run(impossible).fills()).isEmpty();
    }

    @Test
    void aTradedSymbolWithNoConfiguredRulesStopsTheRunInsteadOfTradingNothing() {
        Fixture noRules = fixture();
        noRules.rules = List.of();
        // An empty rules block is the default shape of a configuration nobody filled in, and the
        // report of such a run would show a flat curve reading as "this strategy broke even".
        assertThatThrownBy(() -> run(noRules))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No trading rules for BTCUSDT.PERP");
    }

    // ------------------------------------------------------------------ artifacts

    @Test
    void theReportIsWrittenWhereConfiguredAndIsTheOneReturned() throws IOException {
        BacktestReport report = run(fixture());

        Path html = reports().resolve(BacktestWiring.REPORT_FILE);
        assertThat(Files.readString(html, StandardCharsets.UTF_8))
                .isEqualTo(HtmlReportRenderer.render(report));
    }

    @Test
    void theEventJournalIsOffByDefaultAndOverwrittenPerRun() throws IOException {
        run(fixture());
        Path journal = reports().resolve(BacktestWiring.JOURNAL_FILE);
        assertThat(Files.exists(journal)).isFalse();

        Fixture journalled = fixture();
        journalled.journal = true;
        run(journalled);
        long firstRun = Files.size(journal);
        assertThat(firstRun).isGreaterThan(0);

        // JsonlEventJournal appends, so a second run into the same file would concatenate two runs
        // into one journal that replays as a single impossible event stream.
        run(journalled);
        assertThat(Files.size(journal)).isEqualTo(firstRun);
    }

    @Test
    void aRefusedRunLeavesNoReportBehind() {
        Fixture noRange = fixture();
        noRange.from = null;

        assertThatThrownBy(() -> run(noRange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha.backtest.from must be set");
        assertThat(Files.exists(reports().resolve(BacktestWiring.REPORT_FILE))).isFalse();
    }

    @Test
    void bothEndsOfTheRangeAreRequired() {
        Fixture noTo = fixture();
        noTo.to = null;
        assertThatThrownBy(() -> run(noTo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha.backtest.to must be set");
    }

    // ------------------------------------------------------------------ defaults

    @Test
    void anAbsentBacktestBlockStillHasAUsableShape() {
        AlphaProperties properties = new AlphaProperties(
                "backtest", null, null, true, null, null, null, null, null, null, null);
        AlphaProperties.Backtest backtest = properties.backtest();

        assertThat(backtest.data().source()).isEqualTo("csv");
        assertThat(backtest.data().csvDir()).isEqualTo(Path.of("data", "klines"));
        assertThat(backtest.reportDir()).isEqualTo(Path.of("reports"));
        assertThat(backtest.journal()).isFalse();
        assertThat(backtest.series()).isEmpty();
        assertThat(backtest.rules()).isEmpty();

        // Same question for the download block: absent means no range (so the wiring refuses rather
        // than inventing one) and no explicit series (so it falls back to alpha.symbols).
        assertThat(properties.download().from()).isNull();
        assertThat(properties.download().to()).isNull();
        assertThat(properties.download().baseUrl()).isNull();
        assertThat(properties.download().series()).isEmpty();

        // No range on purpose: refusing beats inventing one, because a range wider than the stored
        // data reports the difference as gaps in the dataset.
        assertThat(backtest.from()).isNull();
        assertThat(backtest.to()).isNull();

        // And these stay unset rather than defaulting here, so the numbers in
        // PositionSizer.Policy, SimulatedExecutor.CostModel and EventEngine remain the only ones.
        assertThat(properties.risk().sizing().targetExposure()).isNull();
        assertThat(properties.risk().sizing().effectiveTargetExposure())
                .isEqualTo(PositionSizer.Policy.DEFAULT.targetExposure());
        assertThat(backtest.cost()).isNull();
        assertThat(backtest.quiescenceTimeout()).isNull();
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A working configuration; each test overrides the one knob it is about. The range, the rules
     * and the strategy are the three that have to be right for anything to trade at all.
     */
    private final class Fixture {
        private String source = "csv";
        private String jdbcUrl;
        private Instant from = Instant.ofEpochMilli(T0);
        private Instant to = Instant.ofEpochMilli(T0 + (BARS - 1) * HOUR);
        private List<AlphaProperties.Backtest.SeriesEntry> series = List.of();
        private BigDecimal targetExposure;
        private BigDecimal maxOrderNotionalFraction;
        private BigDecimal maxPortfolioNotionalFraction;
        private AlphaProperties.Backtest.Cost cost;
        private List<AlphaProperties.Backtest.RuleEntry> rules = List.of(
                new AlphaProperties.Backtest.RuleEntry("BTCUSDT.PERP", BTC_RULES.tickSize(),
                        BTC_RULES.stepSize(), BTC_RULES.minNotional()));
        private boolean journal;
        private BigDecimal initialCash = EQUITY;

        private AlphaProperties.Backtest backtest() {
            return new AlphaProperties.Backtest(
                    new AlphaProperties.Backtest.Data(source, klines(), jdbcUrl),
                    from, to, series, cost, rules, reports(), journal, null);
        }

        /**
         * Only sizing is overridden by default; the other four levels stay at DESIGN §8's defaults,
         * and since T308 they are <em>enforced</em> rather than declared. Two caps therefore have a
         * knob: the order cap, because it is coupled to the exposure and raising one without the other
         * is refused at binding time (取舍 17), and the portfolio cap, because a test that wants a
         * bigger order than the shipped 30%/60% allows has to say so.
         */
        private AlphaProperties.Risk risk() {
            AlphaProperties.Risk defaults = AlphaProperties.Risk.DEFAULTS;
            AlphaProperties.Risk.Order order = maxOrderNotionalFraction == null
                    ? defaults.order()
                    : new AlphaProperties.Risk.Order(true, maxOrderNotionalFraction,
                            defaults.order().maxPriceDeviation());
            // One value for both fractions: equal is legal (the record refuses only symbol > total),
            // and a test that lifts the caps wants neither of them to be the one that binds.
            AlphaProperties.Risk.Portfolio portfolio = maxPortfolioNotionalFraction == null
                    ? defaults.portfolio()
                    : new AlphaProperties.Risk.Portfolio(true, maxPortfolioNotionalFraction,
                            maxPortfolioNotionalFraction);
            return new AlphaProperties.Risk(defaults.account(), order, portfolio,
                    defaults.breaker(), defaults.frequency(),
                    new AlphaProperties.Risk.Sizing(targetExposure));
        }
    }

    /** Writes the fixture dataset, because every test needs bars to replay. */
    private Fixture fixture() {
        store().save(BTC, Interval.H1, bars(BARS));
        return new Fixture();
    }

    private BacktestReport run(Fixture fixture) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        // Short windows so a 24-bar fixture contains a cross at all; allow-short off so the one
        // signal in the run is the golden cross and the first fill is unambiguous.
        params.put("fast-period", "3");
        params.put("slow-period", "5");
        params.put("allow-short", "false");
        AlphaProperties properties = new AlphaProperties("backtest", List.of("BTCUSDT.PERP"), "1h",
                true, new AlphaProperties.Trading(false), Path.of("logs"), fixture.initialCash,
                fixture.risk(),
                List.of(new AlphaProperties.StrategyEntry("ma-btc", "ma-cross", true, null, null, params)),
                fixture.backtest(), null);
        return new BacktestWiring(properties, new StrategyRegistry()).backtest();
    }

    private Path klines() {
        return directory.resolve("klines");
    }

    private Path reports() {
        return directory.resolve("reports");
    }

    private CsvKlineRepository store() {
        return new CsvKlineRepository(klines());
    }

    private static SQLiteDataSource dataSource(String url) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    /**
     * Twelve bars down from 100 and twelve back up: a V with a clear bottom, so a 3/5 cross fires
     * once on the way up and the fixture does not depend on exactly where inside the window it
     * fires. Each bar opens at the previous close, so every bar has a non-zero amplitude for the
     * slippage model to work on.
     */
    private static List<Kline> bars(int count) {
        int bottom = count / 2;
        List<Kline> bars = new ArrayList<>();
        BigDecimal open = new BigDecimal("101");
        for (int i = 0; i < count; i++) {
            BigDecimal close = i < bottom
                    ? BigDecimal.valueOf(100L - i)
                    : BigDecimal.valueOf(100L - bottom).add(BigDecimal.valueOf(i - bottom + 1L));
            BigDecimal high = open.max(close).add(new BigDecimal("0.5"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("0.5"));
            bars.add(new Kline(T0 + i * HOUR, open, high, low, close,
                    new BigDecimal("1000"), T0 + (i + 1) * HOUR - 1));
            open = close;
        }
        return bars;
    }

    private static SimulatedExecutor.SimulatedFill firstFill(BacktestReport report) {
        assertThat(report.fills()).isNotEmpty();
        return report.fills().get(0);
    }
}
