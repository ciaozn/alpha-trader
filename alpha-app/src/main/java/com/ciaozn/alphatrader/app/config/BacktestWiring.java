package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.backtest.BacktestRunner;
import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.Series;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor;
import com.ciaozn.alphatrader.backtest.report.BacktestReport;
import com.ciaozn.alphatrader.backtest.report.ConsoleSummary;
import com.ciaozn.alphatrader.backtest.report.HtmlReportRenderer;
import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.JsonlEventJournal;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.config.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Backtest mode: run one replay over the configured range, write the report, and let the process
 * exit (T219, FR-OP-01). Nothing here implements trading - it reads {@code alpha.backtest.*} and
 * hands {@link BacktestRunner} a configuration.
 *
 * <p><b>Why the process exits.</b> This profile deliberately has no {@code EventEngine} bean and no
 * {@link StartupWiring}: the runner builds its own engine, clock and journal per run, because all
 * three are per-run objects rather than singletons - a container-managed {@code VirtualClock} would
 * have to start at the first bar of whichever run happens to be configured, and a container-managed
 * engine would outlive the replay and hold the JVM open with its non-daemon loop thread. With none
 * of them started, {@code main} returns, only daemon threads remain, and the JVM exits on its own.
 * A failed backtest throws out of this runner, which Spring Boot turns into a non-zero exit code -
 * what CI needs to tell "no result" from "bad result".
 *
 * <p><b>What is configured here and what is not.</b> Only the backtest-specific parts: the stored
 * bars, the range, the simulated costs, the precision rules and the report destination. Starting
 * equity is {@code alpha.initial-cash} and strategies are {@code alpha.strategies} - the same knobs
 * paper and live read. That is FR-BT-06 from the configuration side: if a backtest could be given a
 * different book or a different strategy set than the live system, its result would stop being
 * evidence about live trading.
 *
 * <p>The report directory holds the artifacts of the latest run: the HTML is overwritten, and so is
 * the event journal when one is enabled, because {@link JsonlEventJournal} appends and two runs
 * concatenated into one file would replay as a single impossible event stream.
 */
@Component
@Profile("backtest")
@Order(1)
public class BacktestWiring implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestWiring.class);

    static final String REPORT_FILE = "backtest-report.html";
    static final String JOURNAL_FILE = "events.jsonl";

    private final AlphaProperties properties;
    private final StrategyRegistry registry;

    public BacktestWiring(AlphaProperties properties, StrategyRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        backtest();
    }

    /**
     * Runs one backtest, writes its report and returns it, so a caller can read the numbers without
     * parsing the HTML back.
     *
     * <p>Repeatable, and repeating it is how SC-02 is demonstrated: every call builds the strategies
     * again, because a {@code Strategy} carries state across bars - indicator windows, whether a
     * cross has already fired - so replaying into the same instances a second time is not a second
     * run. Three calls on one wiring produce three independent runs to compare bit for bit.
     */
    public BacktestReport backtest() throws IOException {
        AlphaProperties.Backtest backtest = properties.backtest();
        Path directory = Files.createDirectories(backtest.reportDir());
        BacktestReport report = new BacktestRunner(config(backtest, directory)).run();

        Path html = directory.resolve(REPORT_FILE);
        Files.writeString(html, HtmlReportRenderer.render(report), StandardCharsets.UTF_8);
        // The console summary is the one place the run's wall-clock duration is printed: SC-01 is a
        // timing acceptance, so the number has to be readable from the run itself. It never reaches
        // the HTML, which is compared byte for byte between runs.
        log.info("\n{}", ConsoleSummary.render(report));
        log.info("Backtest report written to {}", html.toAbsolutePath());
        return report;
    }

    private BacktestRunner.Config config(AlphaProperties.Backtest backtest, Path directory) {
        // Validated before anything touches the filesystem: a run refused for a missing range
        // should not leave a database file or a journal behind on the way out.
        long from = millis(backtest.from(), "alpha.backtest.from");
        long to = millis(backtest.to(), "alpha.backtest.to");
        List<Strategy> strategies = registry.build(StrategyConfig.specs(properties));
        TradingRulesProvider rules = rules(backtest);
        KlineRepository repository = DataPlan.store(backtest.data());
        List<Series> series = DataPlan.series(backtest.series(), strategies);
        return new BacktestRunner.Config(repository, series, from, to, properties.initialCash(),
                rules, strategies, policy(properties.risk().sizing()), RiskPipeline.empty(), costModel(backtest),
                quiescenceTimeout(backtest), journal(backtest, directory));
    }

    /**
     * There is no default range, and inventing one would be worse than refusing: the feeder reports
     * every bar between the requested bounds and the stored data as a gap (spec edge case 3), so a
     * range wider than the dataset fills the report with holes that are not in the data.
     */
    private static long millis(Instant value, String property) {
        if (value == null) {
            throw new IllegalStateException(property + " must be set (ISO-8601 instant, UTC): "
                    + "a backtest replays a stated range, and a range guessed wider than the stored "
                    + "data would report the difference as gaps in the dataset");
        }
        return value.toEpochMilli();
    }

    private static TradingRulesProvider rules(AlphaProperties.Backtest backtest) {
        List<TradingRules> rules = new ArrayList<>();
        for (AlphaProperties.Backtest.RuleEntry entry : backtest.rules()) {
            rules.add(new TradingRules(Symbol.parse(entry.symbol()), entry.tickSize(),
                    entry.stepSize(), entry.minNotional()));
        }
        return new FixedTradingRulesProvider(rules);
    }

    private static PositionSizer.Policy policy(AlphaProperties.Risk.Sizing sizing) {
        // effectiveTargetExposure() substitutes PositionSizer.Policy.DEFAULT, so the number has one
        // definition and there is no second "unset" branch here to drift away from it.
        return new PositionSizer.Policy(sizing.effectiveTargetExposure());
    }

    /** Per-field fallback, so {@code cost: {taker-fee-rate: 0}} means "no fee, default everything else". */
    private static SimulatedExecutor.CostModel costModel(AlphaProperties.Backtest backtest) {
        AlphaProperties.Backtest.Cost cost = backtest.cost();
        if (cost == null) {
            return SimulatedExecutor.CostModel.DEFAULT;
        }
        SimulatedExecutor.CostModel defaults = SimulatedExecutor.CostModel.DEFAULT;
        return new SimulatedExecutor.CostModel(
                orDefault(cost.takerFeeRate(), defaults.takerFeeRate()),
                orDefault(cost.fixedSlippageBps(), defaults.fixedSlippageBps()),
                orDefault(cost.amplitudeFactor(), defaults.amplitudeFactor()),
                orDefault(cost.fundingRatePerInterval(), defaults.fundingRatePerInterval()));
    }

    private static BigDecimal orDefault(BigDecimal configured, BigDecimal fallback) {
        return configured == null ? fallback : configured;
    }

    private static Duration quiescenceTimeout(AlphaProperties.Backtest backtest) {
        return backtest.quiescenceTimeout() == null
                ? EventEngine.DEFAULT_QUIESCENCE_TIMEOUT
                : backtest.quiescenceTimeout();
    }

    /**
     * Off by default and not only for tidiness: three years of hourly bars publish several events
     * per bar, and serialising all of them to JSONL competes directly with SC-01's five minutes.
     * It is a debugging aid for a single short run, which is why it lives next to that run's report.
     */
    private static EventJournal journal(AlphaProperties.Backtest backtest, Path directory) {
        if (!backtest.journal()) {
            return EventJournal.noop();
        }
        Path path = directory.resolve(JOURNAL_FILE);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot clear the previous event journal at " + path, e);
        }
        return new JsonlEventJournal(path);
    }
}
