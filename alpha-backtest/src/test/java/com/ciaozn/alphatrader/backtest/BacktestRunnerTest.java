package com.ciaozn.alphatrader.backtest;

import com.ciaozn.alphatrader.backtest.data.CsvKlineRepository;
import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.Series;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor;
import com.ciaozn.alphatrader.backtest.report.BacktestReport;
import com.ciaozn.alphatrader.backtest.report.HtmlReportRenderer;
import com.ciaozn.alphatrader.backtest.report.PerformanceAnalyzer;
import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.EventPublisher;
import com.ciaozn.alphatrader.risk.OrderFacts;
import com.ciaozn.alphatrader.risk.OrderRule;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.RiskRejection;
import com.ciaozn.alphatrader.risk.RiskRule;
import com.ciaozn.alphatrader.risk.SignalFacts;
import com.ciaozn.alphatrader.risk.SignalRule;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first place all seven components meet, so these tests assert on what came out of the far end
 * rather than on how the runner is built. The fixture is designed so that the one wiring mistake
 * this class could make - registering the strategies before the simulated exchange - changes three
 * observable numbers at once, because the order would then be placed before the exchange had seen
 * the signal bar and would fill on that same bar's open.
 *
 * <p>Bar 0 opens at 100 and closes at 101; bar 1 opens at 102. Opens and closes are deliberately
 * different so that "filled at the next bar's open" cannot be confused with "filled at the signal
 * bar's close".
 */
class BacktestRunnerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_006_400_000L;
    private static final long HOUR = 3_600_000L;
    private static final int BARS = 10;
    private static final BigDecimal EQUITY = Money.of("10000");
    private static final TradingRules BTC_RULES = new TradingRules(BTC,
            new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("5"));

    // ------------------------------------------------------------------ the chain end to end

    @Test
    void theWholeChainRunsAndProducesAReport() {
        BacktestReport report = new BacktestRunner(config(store(), new EnterWhenFlat(BTC))).run();

        assertThat(report.replay().barsReplayed()).isEqualTo(BARS);
        assertThat(report.replay().hasGaps()).isFalse();
        assertThat(report.series()).containsExactly(new Series(BTC, Interval.H1));
        assertThat(report.curve().points()).hasSize(BARS);
        assertThat(report.curve().startingEquity()).isEqualByComparingTo(EQUITY);

        assertThat(report.fills()).hasSize(1);
        SimulatedExecutor.SimulatedFill fill = report.fills().get(0);
        assertThat(fill.symbol()).isEqualTo(BTC);
        // Policy.DEFAULT's 0.10 exposure x 10000 equity / 101 mark (bar 0's close), floored to
        // stepSize 0.001: 1000 / 101 = 9.90099 -> 9.900.
        assertThat(fill.qty()).isEqualByComparingTo("9.900");
        // 5bp fixed + 5% of bar 0's 300bp amplitude, then bar 1's open of 102 slipped up and
        // aligned to tickSize 0.01 against the trader: 102 x 1.0020 = 102.204 -> 102.21.
        assertThat(fill.slippageBps()).isEqualByComparingTo("20");
        assertThat(fill.barOpen()).isEqualByComparingTo("102");
        assertThat(fill.fillPrice()).isEqualByComparingTo("102.21");

        // Never closed, so the run ends with an open position and an open trade, and the
        // statistics that need a closed trade are undefined rather than zero.
        assertThat(report.openPositions()).hasSize(1);
        assertThat(report.metrics().openTrades()).isEqualTo(1);
        assertThat(report.metrics().closedTrades()).isZero();
        assertThat(report.metrics().winRate()).isEmpty();
        assertThat(report.metrics().payoffRatio()).isEmpty();
        assertThat(report.metrics().finalEquity()).isNotEqualByComparingTo(EQUITY);
        assertThat(report.hasUnresolved()).isTrue();
    }

    @Test
    void theExchangeSeesABarBeforeTheStrategiesDo() {
        SimulatedExecutor.SimulatedFill fill =
                new BacktestRunner(config(store(), new EnterWhenFlat(BTC))).run().fills().get(0);

        // Bar 0's close produced the signal; the fill is on bar 1 (FR-BT-02). Registered the other
        // way round the exchange would not yet have seen bar 0 when the order arrived, so it would
        // park it with the fixed slippage alone and then fill it on bar 0's own open of 100.
        assertThat(fill.fillBarOpenTime()).isEqualTo(T0 + HOUR);
        assertThat(fill.fillBarOpenTime()).isNotEqualTo(T0);
        assertThat(fill.slippageBps()).isGreaterThan(SimulatedExecutor.CostModel.DEFAULT.fixedSlippageBps());
        assertThat(fill.fillPrice()).isGreaterThan(fill.barOpen());
    }

    /**
     * The fourth wiring rule, and the one whose absence is quietest. The gate <em>consults</em> its
     * rules but never forwards events to them, so a rule that also watches the bus - the shape
     * {@code CircuitBreaker} has, recovering its consecutive-loss count from {@code FillEvent}s - has to
     * be registered separately. Left off the engine it is still consulted on every signal, still looks
     * configured in the log and in the pipeline, and simply never learns that anything filled: the
     * breaker's losing-streak trigger goes dark while the daily-loss one keeps working, so the failure
     * is a rule that fires less often, not a rule that errors.
     */
    @Test
    void aRuleThatWatchesTheBusIsRegisteredOnIt() {
        WatchingRule signalStage = new WatchingRule("signal-watcher");
        WatchingRule orderStage = new WatchingRule("order-watcher");
        BacktestReport report = new BacktestRunner(new BacktestRunner.Config(store(),
                List.of(new Series(BTC, Interval.H1)), T0, T0 + (BARS - 1) * HOUR, EQUITY, rules(),
                List.of(new EnterWhenFlat(BTC)), PositionSizer.Policy.DEFAULT,
                RiskPipelineFactory.fixed(new RiskPipeline(List.of(signalStage), List.of(orderStage))),
                SimulatedExecutor.CostModel.DEFAULT, EventEngine.DEFAULT_QUIESCENCE_TIMEOUT,
                EventJournal.noop())).run();

        assertThat(report.fills()).hasSize(1);
        // Both stages, not just the one the shipped breaker happens to sit in: sweeping one would leave
        // an order-stage rule that one day needs the bus silently off it.
        assertThat(signalStage.seen).as("what reached the signal-stage rule").contains(FillEvent.class);
        assertThat(orderStage.seen).as("what reached the order-stage rule").contains(FillEvent.class);
        // Registering them must not replace the pipeline's own use of them: one signal, one order, so
        // one consultation of each stage.
        assertThat(signalStage.consultations).isEqualTo(1);
        assertThat(orderStage.consultations).isEqualTo(1);
    }

    @Test
    void fundingCrossingAnEightHourBoundaryReachesTheReport() {
        BacktestReport report = new BacktestRunner(config(store(), new EnterWhenFlat(BTC))).run();

        // T0 is 2023-11-15T00:00:00Z, so bar 8's close is the first one past the 08:00 boundary,
        // and the position opened on bar 1 is still there to be charged.
        assertThat(report.funding()).hasSize(1);
        assertThat(report.funding().get(0).symbol()).isEqualTo(BTC);
        assertThat(report.funding().get(0).boundaryMillis()).isEqualTo(T0 + 8 * HOUR);
        assertThat(report.fundingTotal()).isNotEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theLastBarsSignalIsReportedAsStillPending() {
        BacktestReport report =
                new BacktestRunner(config(store(), new SignalOnceAtBar(BTC, BARS - 1))).run();

        // No bar follows the last one, so the order can never fill. Dropping it would hide the fact
        // that the run ended mid-intent.
        assertThat(report.fills()).isEmpty();
        assertThat(report.pendingOrders()).hasSize(1);
        assertThat(report.pendingOrders().get(0).symbol()).isEqualTo(BTC);
        assertThat(report.hasUnresolved()).isTrue();
        assertThat(HtmlReportRenderer.render(report)).contains(report.pendingOrders().get(0).clientOrderId());
    }

    // ------------------------------------------------------------------ reproducibility

    @Test
    void twoIndependentlyAssembledRunsAgreeByteForByte() {
        // Two configs, two runners, two sets of strategies: SC-02 compares three whole runs, and a
        // runner is only reusable if nothing about the first run leaks into the second.
        String first = HtmlReportRenderer.render(
                new BacktestRunner(config(store(), new EnterWhenFlat(BTC))).run());
        String second = HtmlReportRenderer.render(
                new BacktestRunner(config(store(), new EnterWhenFlat(BTC))).run());

        assertThat(second).isEqualTo(first);
    }

    @Test
    void theDataStoreIsInterchangeableWithoutTouchingTheAssembly(@TempDir Path directory) {
        List<Kline> bars = hourlyBars(BARS);
        MapKlineRepository inMemory = new MapKlineRepository();
        inMemory.put(BTC, Interval.H1, bars);
        CsvKlineRepository onDisk = new CsvKlineRepository(directory);
        onDisk.save(BTC, Interval.H1, bars);

        String fromMemory = HtmlReportRenderer.render(
                new BacktestRunner(config(inMemory, new EnterWhenFlat(BTC))).run());
        String fromDisk = HtmlReportRenderer.render(
                new BacktestRunner(config(onDisk, new EnterWhenFlat(BTC))).run());

        // FR-BT-06: swapping the store must not reach the feeder, the strategies or the matcher.
        assertThat(fromDisk).isEqualTo(fromMemory);
    }

    @Test
    void theSamplingPeriodIsTheFinestIntervalInTheRun() {
        MapKlineRepository store = store();
        store.put(ETH, Interval.H4, fourHourBars(3));
        // ETH first, so "the first series' interval" and "the finest interval" disagree.
        BacktestReport report = new BacktestRunner(new BacktestRunner.Config(store,
                List.of(new Series(ETH, Interval.H4), new Series(BTC, Interval.H1)),
                T0, T0 + (BARS - 1) * HOUR, EQUITY, rules(), List.of(new EnterWhenFlat(BTC)))).run();

        assertThat(report.replay().barsReplayed()).isEqualTo(BARS + 3);
        // ETH's H4 closes land on two of BTC's H1 closes, and equity as of an instant means after
        // everything at that instant, so those rounds replace a point rather than adding one.
        assertThat(report.curve().points()).hasSize(BARS + 1);
        assertThat(report.metrics()).isEqualTo(PerformanceAnalyzer.analyze(
                report.curve(), report.trades(), Duration.ofHours(1)));
    }

    // ------------------------------------------------------------------ refusing to run

    @Test
    void theEngineThreadIsStoppedEvenWhenTheReplayFails() {
        long before = engineThreads();
        // An inverted range: the feeder refuses it, after the runner has already started the engine.
        BacktestRunner.Config config = new BacktestRunner.Config(store(),
                List.of(new Series(BTC, Interval.H1)), T0 + HOUR, T0, EQUITY, rules(),
                List.of(new EnterWhenFlat(BTC)));

        assertThatThrownBy(() -> new BacktestRunner(config).run())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Empty range");

        // The loop thread is not a daemon: leaking one holds the JVM, or a surefire fork, open
        // after the failure has already been reported.
        assertThat(engineThreads()).isEqualTo(before);
    }

    @Test
    void aRunWithNoStrategiesIsRefused() {
        assertThatThrownBy(() -> new BacktestRunner.Config(store(),
                List.of(new Series(BTC, Interval.H1)), T0, T0 + HOUR, EQUITY, rules(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no strategies");
    }

    @Test
    void aNonPositiveAccountIsRefused() {
        // The exact wording matters: EquityCurve refuses a non-positive baseline too, three
        // components later, with a message about the curve. The point of guarding here is that the
        // failure names the money and arrives before ten bars have been replayed for nothing.
        assertThatThrownBy(() -> new BacktestRunner.Config(store(),
                List.of(new Series(BTC, Interval.H1)), T0, T0 + HOUR, BigDecimal.ZERO, rules(),
                List.of(new EnterWhenFlat(BTC))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startingEquity must be positive");
    }

    @Test
    void aTradedSymbolWithNoTradingRulesIsRefused() {
        // rules() knows BTC only. Replaying a symbol with no rules is legitimate - the
        // sampling-period test above replays ETH and never trades it - but trading one is not:
        // the gate would block every signal as RK-07, the run would replay all ten bars, and the
        // report would show a flat curve reading as "this strategy broke even".
        assertThatThrownBy(() -> new BacktestRunner.Config(store(),
                List.of(new Series(BTC, Interval.H1)), T0, T0 + HOUR, EQUITY, rules(),
                List.of(new EnterWhenFlat(ETH))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No trading rules for ETHUSDT.PERP");
    }

    // ------------------------------------------------------------------ fixtures

    private static BacktestRunner.Config config(KlineRepository repository, Strategy strategy) {
        return new BacktestRunner.Config(repository, List.of(new Series(BTC, Interval.H1)),
                T0, T0 + (BARS - 1) * HOUR, EQUITY, rules(), List.of(strategy));
    }

    private static TradingRulesProvider rules() {
        return FixedTradingRulesProvider.of(BTC_RULES);
    }

    private static MapKlineRepository store() {
        MapKlineRepository store = new MapKlineRepository();
        store.put(BTC, Interval.H1, hourlyBars(BARS));
        return store;
    }

    /** Bar {@code i} opens at {@code 100 + 2i} and closes at {@code 101 + 2i}, so open != close. */
    private static List<Kline> hourlyBars(int count) {
        List<Kline> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long openTime = T0 + i * HOUR;
            BigDecimal open = BigDecimal.valueOf(100 + 2L * i);
            bars.add(new Kline(openTime, open, open.add(BigDecimal.valueOf(2)),
                    open.subtract(BigDecimal.ONE), open.add(BigDecimal.ONE),
                    BigDecimal.valueOf(1000 + i), openTime + HOUR - 1));
        }
        return bars;
    }

    private static List<Kline> fourHourBars(int count) {
        long step = 4 * HOUR;
        List<Kline> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long openTime = T0 + i * step;
            bars.add(new Kline(openTime, BigDecimal.valueOf(50), BigDecimal.valueOf(51),
                    BigDecimal.valueOf(49), BigDecimal.valueOf(50), BigDecimal.valueOf(5000 + i),
                    openTime + step - 1));
        }
        return bars;
    }

    private static long engineThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "event-engine".equals(thread.getName()))
                .count();
    }

    /**
     * A rule that also watches the bus - the shape {@code CircuitBreaker} has. It objects to nothing,
     * so the run it is wired into trades exactly as it would with an empty pipeline, and the only thing
     * it reports is what reached it and by which of its two roles. It implements both stage interfaces
     * (their {@code check} methods take different facts, so one class can hold both) so the same double
     * can be put in either stage.
     */
    private static final class WatchingRule implements SignalRule, OrderRule, EventHandler {

        private final String id;
        private final List<Class<?>> seen = new ArrayList<>();
        private int consultations;

        WatchingRule(String id) {
            this.id = id;
        }

        @Override
        public String ruleId() {
            return id;
        }

        @Override
        public RiskRule.Level level() {
            return RiskRule.Level.CIRCUIT_BREAKER;
        }

        @Override
        public Optional<RiskRejection> check(SignalFacts facts) {
            consultations++;
            return Optional.empty();
        }

        @Override
        public Optional<RiskRejection> check(OrderFacts facts) {
            consultations++;
            return Optional.empty();
        }

        @Override
        public void onEvent(Event event, EventPublisher publisher) {
            seen.add(event.getClass());
        }
    }

    /** Long whenever the book is flat, so exactly one order survives a correctly ordered run. */
    private static final class EnterWhenFlat implements Strategy {

        private final Symbol symbol;

        EnterWhenFlat(Symbol symbol) {
            this.symbol = symbol;
        }

        @Override
        public String id() {
            return "enter-when-flat";
        }

        @Override
        public Set<Symbol> symbols() {
            return new LinkedHashSet<>(List.of(symbol));
        }

        @Override
        public Interval interval() {
            return Interval.H1;
        }

        @Override
        public void onKline(KlineEvent event, StrategyContext context) {
            if (context.portfolio().position(symbol).isEmpty()) {
                context.emit(symbol, Direction.LONG, 1.0, "book is flat");
            }
        }
    }

    /** Long once, on the {@code barIndex}-th bar it sees - the tail-pending fixture. */
    private static final class SignalOnceAtBar implements Strategy {

        private final Symbol symbol;
        private final int barIndex;
        private int seen;

        SignalOnceAtBar(Symbol symbol, int barIndex) {
            this.symbol = symbol;
            this.barIndex = barIndex;
        }

        @Override
        public String id() {
            return "signal-once";
        }

        @Override
        public Set<Symbol> symbols() {
            return new LinkedHashSet<>(List.of(symbol));
        }

        @Override
        public Interval interval() {
            return Interval.H1;
        }

        @Override
        public void onKline(KlineEvent event, StrategyContext context) {
            if (seen++ == barIndex) {
                context.emit(symbol, Direction.LONG, 1.0, "last bar of the fixture");
            }
        }
    }

    private static final class MapKlineRepository implements KlineRepository {

        private final Map<String, List<Kline>> store = new LinkedHashMap<>();

        void put(Symbol symbol, Interval interval, List<Kline> bars) {
            store.put(key(symbol, interval), List.copyOf(bars));
        }

        @Override
        public List<Kline> load(Symbol symbol, Interval interval, long fromOpenTime, long toOpenTime) {
            return store.getOrDefault(key(symbol, interval), List.of()).stream()
                    .filter(bar -> bar.openTime() >= fromOpenTime && bar.openTime() <= toOpenTime)
                    .toList();
        }

        @Override
        public int save(Symbol symbol, Interval interval, List<Kline> klines) {
            put(symbol, interval, klines);
            return klines.size();
        }

        private static String key(Symbol symbol, Interval interval) {
            return symbol.unified() + "|" + interval.binanceCode();
        }
    }
}
