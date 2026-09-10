package com.ciaozn.alphatrader.backtest;

import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder;
import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.ReplaySummary;
import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder.Series;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor;
import com.ciaozn.alphatrader.backtest.report.BacktestReport;
import com.ciaozn.alphatrader.backtest.report.EquityRecorder;
import com.ciaozn.alphatrader.backtest.report.PerformanceAnalyzer;
import com.ciaozn.alphatrader.backtest.report.PerformanceMetrics;
import com.ciaozn.alphatrader.backtest.report.TradeTracker;
import com.ciaozn.alphatrader.common.data.KlineRepository;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.risk.InMemoryRecordStore;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RiskGate;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.SignalRecorder;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Assembles and runs one complete backtest in plain Java (FR-BT-06): data store, virtual clock,
 * event engine, replay feeder, simulated exchange, risk gate, strategies, performance analysis and
 * the report bundle. No Spring anywhere in this module - the container is an alpha-app concern, and
 * keeping the assembly constructible by hand is what lets a test drive the whole chain over a fixed
 * dataset without booting anything.
 *
 * <p><b>Isomorphism.</b> Every component here except the feeder, the executor and the clock is the
 * same class the paper and live profiles use. That is the point of FR-BT-06, and it is why this
 * class wires rather than implements: the moment a backtest needed its own strategy dispatch or its
 * own position sizing, a result measured here would stop being evidence about live trading.
 *
 * <p><b>The four wiring rules that the code below cannot show.</b>
 * <ul>
 *   <li>{@link SimulatedExecutor} is registered before {@link StrategyEngine} because both consume
 *       {@code KlineEvent} and registration order is dispatch order. The executor has to see a bar
 *       first: it fills the previous bar's order at this bar's open and settles funding, and only
 *       then may strategies reason about this bar. Reversed, a strategy would decide against a
 *       position the exchange has already changed - not a look-ahead leak, but just as wrong.</li>
 *   <li>A rule from the pipeline that watches the bus is registered on it, between the gate and the
 *       strategy engine. The gate consults its rules but never forwards events to them, so without
 *       this the circuit breaker would be consulted, look configured, and never learn about a fill -
 *       losing its consecutive-loss trigger entirely. Its position in the list is for readability, not
 *       correctness: the engine runs the whole handler loop before it drains the cascade, so a signal
 *       a strategy emits in reaction to a {@code FillEvent} is dispatched after every handler, the
 *       breaker included, has already seen that fill.</li>
 *   <li>{@link EquityRecorder} is handed to the feeder as a {@code RoundListener} and is
 *       <em>not</em> registered as a handler. "This bar's signal -&gt; risk -&gt; order -&gt; fill
 *       cascade has closed" cannot be expressed by handler order; a sampler relying on being
 *       registered last stays correct only until someone registers one more handler.</li>
 *   <li>Nothing here writes the {@link Portfolio} except the executor and the strategy engine's
 *       mark. The executor keeps the book itself before publishing {@code FillEvent}, exactly as
 *       the live OMS does, so a second book-keeping handler would double-count every fill.</li>
 * </ul>
 *
 * <p>Risk blocks are deliberately not part of the report: {@link RiskGate} already logs each one at
 * WARN with its rule id, and the counts are summarised in the run log below. Putting them in
 * {@link BacktestReport} as well would give the reader a second, differently-filtered view of the
 * same information.
 */
public final class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);

    private final Config config;

    public BacktestRunner(Config config) {
        this.config = config;
    }

    /**
     * Everything one run needs. The convenience constructor fills in the five inputs that have an
     * obviously correct default; the canonical one exists so a caller - alpha-app reading yml, a
     * cost-sensitivity sweep - can override any of them. The pipeline is a {@link RiskPipelineFactory}
     * rather than a {@link RiskPipeline} because the circuit breaker is built against the run's book,
     * which does not exist yet; {@code RiskPipelineFactory.fixed(RiskPipeline.empty())} means "no rules
     * beyond sizing", which is <em>not</em> what a shipped configuration should use: the backtest is
     * only evidence about live trading if both run the same rules (FR-BT-06).
     *
     * <p>Three configurations are refused rather than replayed, all because each would produce a
     * report that looks like a result: no strategies, a non-positive account, and a symbol a
     * strategy trades with no trading rules behind it. Each replays every bar, trades nothing and
     * reports a flat curve indistinguishable from a strategy that broke even, and each is normally
     * a configuration typo.
     */
    public record Config(
            KlineRepository repository,
            List<Series> series,
            long fromOpenTime,
            long toOpenTime,
            BigDecimal startingEquity,
            TradingRulesProvider tradingRules,
            List<Strategy> strategies,
            PositionSizer.Policy sizerPolicy,
            RiskPipelineFactory riskPipelineFactory,
            SimulatedExecutor.CostModel costModel,
            Duration quiescenceTimeout,
            EventJournal journal) {

        public Config {
            if (startingEquity == null || startingEquity.signum() <= 0) {
                // Caught here rather than left to EquityCurve: a non-positive account sizes no
                // position at all, so such a run replays every bar, trades nothing, and then fails
                // at analysis time with a message about the curve instead of about the money.
                throw new IllegalArgumentException("startingEquity must be positive, got " + startingEquity);
            }
            if (strategies == null || strategies.isEmpty()) {
                throw new IllegalArgumentException("Nothing to backtest: no strategies enabled");
            }
            // Same failure shape as the two above, one level subtler: without tickSize/stepSize the
            // gate blocks every signal as RK-07, so the run replays everything, trades nothing and
            // reports a flat curve that reads as "this strategy broke even". Checked against what
            // the strategies trade rather than what is replayed - an extra context series nobody
            // sends orders for is legitimate and needs no rules.
            for (Strategy strategy : strategies) {
                for (Symbol symbol : strategy.symbols()) {
                    if (tradingRules.find(symbol).isEmpty()) {
                        throw new IllegalArgumentException("No trading rules for " + symbol.unified()
                                + ", which strategy " + strategy.id() + " trades: every signal would be "
                                + "blocked as " + RiskGate.RULE_MISSING_TRADING_RULES);
                    }
                }
            }
            series = List.copyOf(series);
            strategies = List.copyOf(strategies);
        }

        public Config(KlineRepository repository, List<Series> series, long fromOpenTime, long toOpenTime,
                      BigDecimal startingEquity, TradingRulesProvider tradingRules,
                      List<Strategy> strategies) {
            this(repository, series, fromOpenTime, toOpenTime, startingEquity, tradingRules, strategies,
                    PositionSizer.Policy.DEFAULT, RiskPipelineFactory.fixed(RiskPipeline.empty()),
                    SimulatedExecutor.CostModel.DEFAULT,
                    EventEngine.DEFAULT_QUIESCENCE_TIMEOUT, EventJournal.noop());
        }
    }

    /**
     * Replays the configured range once and returns the finished report bundle. The engine is
     * stopped on every path including a failed replay: its loop thread is not a daemon, so a run
     * that threw without stopping it would hold a JVM - or a surefire fork - open afterwards.
     *
     * <p>One config is one run. {@link Strategy} instances carry state across bars - indicator
     * windows, whether a cross has already fired - so calling this twice on the same config would
     * replay into warm strategies. Reproducing a run, which SC-02 does three times over, means
     * building the strategies again, not running the same ones again.
     */
    public BacktestReport run() {
        Portfolio portfolio = new Portfolio(config.startingEquity());
        VirtualClock clock = new VirtualClock(config.fromOpenTime());
        EventEngine engine = new EventEngine(config.journal(), clock);

        SimulatedExecutor executor = new SimulatedExecutor(
                portfolio, config.tradingRules(), config.costModel());
        RiskPipeline pipeline = config.riskPipelineFactory().create(portfolio);
        // Backtest runs without a business database, so the record writers get the in-memory store -
        // which is what InMemoryRecordStore's javadoc says it is for. Nothing reads the rows back yet; a
        // discarding sink would have been smaller but would make the write path unobservable outside
        // risk's own tests, and a row that exists can be surfaced later without touching the writers.
        InMemoryRecordStore records = new InMemoryRecordStore();
        SignalRecorder signalRecorder = new SignalRecorder(records);
        RiskGate riskGate = new RiskGate(portfolio, new PositionSizer(config.sizerPolicy()),
                config.tradingRules(), pipeline, clock, records);
        TradeTracker tradeTracker = new TradeTracker(portfolio);
        StrategyEngine strategyEngine = new StrategyEngine(config.strategies(), portfolio, clock);
        EquityRecorder equityRecorder = new EquityRecorder(portfolio);

        BacktestDataFeeder feeder = new BacktestDataFeeder(engine, clock, config.quiescenceTimeout(),
                equityRecorder::afterRound);

        engine.registerHandler(executor);
        // Ahead of the gate, not behind it: handlers run in registration order, and the interception row
        // the gate writes joins to this one on event_id. See SignalRecorder for which half of that join
        // dangles under each order.
        engine.registerHandler(signalRecorder);
        engine.registerHandler(riskGate);
        registerRuleObservers(engine, pipeline);
        engine.registerHandler(tradeTracker);
        engine.registerHandler(strategyEngine);

        log.info("Backtest start: {} series, [{}, {}], equity={}, strategies={}, exposure={}, "
                        + "takerFee={}, slip={}bp + {}x amplitude, funding={}/8h",
                config.series().size(), config.fromOpenTime(), config.toOpenTime(),
                config.startingEquity().toPlainString(),
                config.strategies().stream().map(Strategy::id).toList(),
                config.sizerPolicy().targetExposure().toPlainString(),
                config.costModel().takerFeeRate().toPlainString(),
                config.costModel().fixedSlippageBps().toPlainString(),
                config.costModel().amplitudeFactor().toPlainString(),
                config.costModel().fundingRatePerInterval().toPlainString());

        engine.start();
        ReplaySummary replay;
        try {
            replay = feeder.replay(config.repository(), config.series(),
                    config.fromOpenTime(), config.toOpenTime());
        } finally {
            engine.stop();
        }

        log.info("Backtest done: bars={}, orders passed={}, signals blocked={}, filled={}, "
                        + "rejected={}, still pending={}, equity samples={}",
                replay.barsReplayed(), riskGate.ordersPassed(), riskGate.signalsBlocked(),
                executor.ordersFilled(), executor.ordersRejected(), executor.pendingOrders().size(),
                equityRecorder.rounds());

        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(equityRecorder.curve(),
                tradeTracker.trades(), samplingPeriod(config.series()));
        return new BacktestReport(replay, config.series(), metrics, equityRecorder.curve(),
                tradeTracker.trades(), executor.fills(), executor.funding(), executor.rejections(),
                executor.pendingOrders(), portfolio.openPositions(), executor.costModel());
    }

    /**
     * Puts every rule that watches the bus on the bus. The gate consults its rules but never forwards
     * events to them, so a stateful rule left off the engine would still be consulted, still look
     * configured, and quietly lose the one trigger it needs the bus for - the circuit breaker's
     * consecutive-loss count, which it recovers from {@code FillEvent}s. Both stages are swept so an
     * order-stage rule that one day needs the bus is not silently left off it.
     */
    private static void registerRuleObservers(EventEngine engine, RiskPipeline pipeline) {
        Stream.concat(pipeline.signalRules().stream(), pipeline.orderRules().stream())
                .filter(EventHandler.class::isInstance)
                .map(EventHandler.class::cast)
                .forEach(engine::registerHandler);
    }

    /**
     * The nominal spacing of the equity samples. One sample is taken per replayed round and a round
     * happens per bar, so the finest interval in the run is the closest thing to a constant spacing
     * there is; a single-series run - every run P2 ships - gets exactly its bar interval.
     *
     * <p>A mixed-interval run has genuinely irregular samples and Sharpe's annualization is then an
     * approximation. That is stated rather than hidden because the alternative, a configured period
     * free to disagree with the data actually replayed, is worse: it would let the number look
     * exact while being wrong.
     */
    private static Duration samplingPeriod(List<Series> series) {
        Duration finest = series.get(0).interval().duration();
        for (Series one : series) {
            Duration duration = one.interval().duration();
            if (duration.compareTo(finest) < 0) {
                finest = duration;
            }
        }
        return finest;
    }
}
