package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.risk.PositionSizer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Central typed config (alpha.*). Defaults are safe: testnet on, trading off. */
@ConfigurationProperties(prefix = "alpha")
public record AlphaProperties(
        String mode,
        List<String> symbols,
        String interval,
        boolean binanceTestnet,
        Trading trading,
        Path journalDir,
        BigDecimal initialCash,
        Risk risk,
        List<StrategyEntry> strategies,
        Backtest backtest,
        Download download,
        Reconciliation reconciliation) {

    public record Trading(boolean enabled) {
    }

    /**
     * The {@code alpha.download} block (T223, FR-BT-05): which series to pull, over what range,
     * and from which REST base.
     *
     * <p><b>The destination is deliberately not here.</b> Downloaded bars go to
     * {@code alpha.backtest.data} - the store the backtest reads. Two knobs for one directory is
     * how an operator ends up with a full CSV directory and a backtest that reports it has no data,
     * which reads like a bug in the strategy rather than a typo in a path. This is the same
     * reasoning that keeps {@code initial-cash} and {@code strategies} out of {@code backtest}.
     *
     * <p><b>The range is here and does not fall back to {@code alpha.backtest.from/to}.</b>
     * Downloading is cumulative and idempotent (the downloader dedupes against what is stored),
     * while a backtest is one stated experiment over part of that history. Sharing the range would
     * make it impossible to hold three years and replay one month.
     *
     * <p>{@code baseUrl} overrides only that one field of {@code BinanceKlineDownloader.Settings};
     * page size, request gap and retry budget stay at the tuned {@code Settings.TESTNET}/{@code
     * Settings.LIVE} values, picked by {@code alpha.binance-testnet}. Restating them in yml would
     * give those numbers a second definition that can drift.
     */
    public record Download(
            Instant from,
            Instant to,
            List<Backtest.SeriesEntry> series,
            String baseUrl) {

        /** What an absent block means: no range, so the wiring refuses rather than inventing one. */
        public static final Download DEFAULTS = new Download(null, null, null, null);

        public Download {
            if (series == null) {
                series = List.of();
            }
        }
    }

    /**
     * The {@code alpha.reconciliation} block (T405, FR-EX-05): what to do with a ghost position - one
     * the exchange holds and this process has no record of. {@code Reconciler} always reports it
     * CRITICAL; this switches only whether it also asks the OMS to flatten it.
     *
     * <p><b>Default off, and the default is the safety property.</b> A position we did not open may be
     * one we do not understand - a manual trade on the same account, a second process, a partial
     * deploy - so the honest default is to make noise and leave it alone. Turning this on is a
     * deliberate choice that the only explanation for an unexplained position is a broken local
     * record, and it belongs in yml where it can be seen rather than in code.
     *
     * <p><b>Auto-close goes through the OMS, never the gateway.</b> {@code Reconciler} stays free of
     * network access and only emits an {@code OrderRequestEvent}; the normal order path writes the row,
     * sends, and settles the fill. A reconciliation pass that called {@code placeOrder} directly would
     * put an order in the world with no row behind it - invisible to the next pass and to recovery.
     *
     * <p>A primitive {@code boolean} rather than a {@code Boolean}: absent must mean false, and there
     * is no "the operator did not say" state to distinguish here - unlike the risk levels, where an
     * absent block must mean DESIGN §8 rather than "off".
     */
    public record Reconciliation(boolean autoCloseGhostPositions) {

        /** What an absent block means: report ghosts, never close them. */
        public static final Reconciliation DEFAULTS = new Reconciliation(false);
    }

    /**
     * The {@code alpha.risk} block (T302): the five rule levels of DESIGN §8 plus the sizing knob,
     * each with its own on/off switch (FR-RK-02..07, FR-RK-09's externalised parameters).
     *
     * <p><b>Declared in the shared {@code application.yml}, not a profile file.</b> These are not
     * backtest knobs: a backtest that ran with looser limits than the live system would stop being
     * evidence about live trading (FR-BT-06), and Spring loads {@code application-<profile>.yml}
     * only for the active profile - the same trap that moved {@code alpha.backtest.data} to the
     * shared file. DESIGN §8 says {@code risk-rules.yml}; that file would be loaded by no profile
     * (取舍 13).
     *
     * <p><b>Every limit is a fraction, not a percentage</b> - 0.20 means 20% of equity. DESIGN §8
     * states them as percentages, so the yml says so too: reading "20" where the rule wants 0.20 is
     * a 100x error no rule test would catch, because the rule would simply be 100x loose and every
     * order would pass.
     *
     * <p><b>{@code @JsonNaming} here and on every nested block below is for the T404 reload endpoint,
     * not for yml binding.</b> Spring's own binder is relaxed and reads {@code max-notional-fraction}
     * regardless; Jackson is not, and the reload request body is Jackson. Making the API speak the same
     * kebab-case keys as the file it edits is what stops an operator copying a key that binds in yml
     * from silently binding to nothing over HTTP - a body read as all-defaults would report a successful
     * reload that changed no limit. It has no effect on {@code @ConfigurationProperties}.
     */
    @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
    public record Risk(
            Account account,
            Order order,
            Portfolio portfolio,
            Breaker breaker,
            Frequency frequency,
            Sizing sizing) {

        /** What an absent block means: every level on, at DESIGN §8's defaults. */
        public static final Risk DEFAULTS = new Risk(null, null, null, null, null, null);

        /**
         * 账户级 (FR-RK-02). {@code minMarginRatio} is <b>this system's own</b> definition,
         * {@code equity / usedMargin} with {@code usedMargin = totalNotional / maxLeverage} - the
         * floor below which no new position may be opened. Binance's field of the same name runs the
         * other way (maintenance margin over position notional, higher is closer to liquidation), so
         * wiring that field in here would pass the orders that are about to be liquidated (取舍 12).
         * The exchange's number belongs to reconciliation, not to this rule.
         */
        @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
        public record Account(Boolean enabled, BigDecimal maxLeverage, BigDecimal minMarginRatio) {

            public static final Account DEFAULTS =
                    new Account(true, new BigDecimal("3"), new BigDecimal("1.50"));

            public Account {
                if (enabled == null) {
                    enabled = Boolean.TRUE;
                }
                if (maxLeverage == null) {
                    maxLeverage = DEFAULTS.maxLeverage();
                }
                require("max-leverage", maxLeverage, BigDecimal.ZERO, null);
                if (minMarginRatio == null) {
                    minMarginRatio = DEFAULTS.minMarginRatio();
                }
                require("min-margin-ratio", minMarginRatio, BigDecimal.ZERO, null);
            }
        }

        /**
         * 订单级 (FR-RK-03). {@code maxPriceDeviation} is the fat-finger guard: a limit price further
         * than this from the last mark is refused. A MARKET order has no price and skips the check -
         * the gate only ever sends MARKET, so the alternative is a rule that looks configured and
         * never fires.
         */
        @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
        public record Order(Boolean enabled, BigDecimal maxNotionalFraction, BigDecimal maxPriceDeviation) {

            public static final Order DEFAULTS =
                    new Order(true, new BigDecimal("0.20"), new BigDecimal("0.02"));

            public Order {
                if (enabled == null) {
                    enabled = Boolean.TRUE;
                }
                if (maxNotionalFraction == null) {
                    maxNotionalFraction = DEFAULTS.maxNotionalFraction();
                }
                require("max-notional-fraction", maxNotionalFraction, BigDecimal.ZERO, null);
                if (maxPriceDeviation == null) {
                    maxPriceDeviation = DEFAULTS.maxPriceDeviation();
                }
                require("max-price-deviation", maxPriceDeviation, BigDecimal.ZERO, BigDecimal.ONE);
            }
        }

        /**
         * 组合级 (FR-RK-04), evaluated against the book <b>as it would be if this order filled</b>:
         * checking the current totals would pass every order that grows an already-oversized book one
         * step at a time. Reducing a position can never be caught by these two - see
         * {@code OrderFacts}.
         */
        @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
        public record Portfolio(Boolean enabled,
                               BigDecimal maxTotalNotionalFraction,
                               BigDecimal maxSymbolNotionalFraction) {

            public static final Portfolio DEFAULTS =
                    new Portfolio(true, new BigDecimal("0.60"), new BigDecimal("0.30"));

            public Portfolio {
                if (enabled == null) {
                    enabled = Boolean.TRUE;
                }
                if (maxTotalNotionalFraction == null) {
                    maxTotalNotionalFraction = DEFAULTS.maxTotalNotionalFraction();
                }
                require("max-total-notional-fraction", maxTotalNotionalFraction, BigDecimal.ZERO, null);
                if (maxSymbolNotionalFraction == null) {
                    maxSymbolNotionalFraction = DEFAULTS.maxSymbolNotionalFraction();
                }
                require("max-symbol-notional-fraction", maxSymbolNotionalFraction, BigDecimal.ZERO, null);
                if (maxSymbolNotionalFraction.compareTo(maxTotalNotionalFraction) > 0) {
                    throw new IllegalArgumentException("alpha.risk.portfolio.max-symbol-notional-fraction ("
                            + maxSymbolNotionalFraction + ") cannot exceed max-total-notional-fraction ("
                            + maxTotalNotionalFraction + "): one symbol is part of the total, so the "
                            + "symbol cap would be the only one that ever fires");
                }
            }
        }

        /**
         * 熔断级 (FR-RK-05). {@code dailyLossFraction} is a magnitude - 0.05 means "down 5% from the
         * UTC day's opening equity" - and stops new openings for the rest of that UTC day;
         * {@code consecutiveLosses} closed losing trades pause trading for {@code pause}.
         */
        @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
        public record Breaker(Boolean enabled,
                             BigDecimal dailyLossFraction,
                             int consecutiveLosses,
                             Duration pause) {

            public static final Breaker DEFAULTS =
                    new Breaker(true, new BigDecimal("0.05"), 3, Duration.ofHours(2));

            public Breaker {
                if (enabled == null) {
                    enabled = Boolean.TRUE;
                }
                if (dailyLossFraction == null) {
                    dailyLossFraction = DEFAULTS.dailyLossFraction();
                }
                require("daily-loss-fraction", dailyLossFraction, BigDecimal.ZERO, BigDecimal.ONE);
                if (consecutiveLosses <= 0) {
                    throw new IllegalArgumentException(
                            "alpha.risk.breaker.consecutive-losses must be >= 1, got " + consecutiveLosses
                                    + ": 0 or less would pause trading on the first losing trade and never resume");
                }
                if (pause == null) {
                    pause = DEFAULTS.pause();
                }
                if (pause.isNegative() || pause.isZero()) {
                    throw new IllegalArgumentException(
                            "alpha.risk.breaker.pause must be positive, got " + pause
                                    + ": a zero pause trips the breaker and releases it in the same instant,"
                                    + " which reads as a breaker that never fired");
                }
            }
        }

        /** 频率级 (FR-RK-06): at most {@code maxOrders} inside a sliding {@code window}. */
        @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
        public record Frequency(Boolean enabled, int maxOrders, Duration window) {

            public static final Frequency DEFAULTS =
                    new Frequency(true, 10, Duration.ofMinutes(1));

            public Frequency {
                if (enabled == null) {
                    enabled = Boolean.TRUE;
                }
                if (maxOrders <= 0) {
                    throw new IllegalArgumentException(
                            "alpha.risk.frequency.max-orders must be >= 1, got " + maxOrders
                                    + ": 0 or less is not a rate limit, it is trading switched off,"
                                    + " and it would say so only through a stream of alerts");
                }
                if (window == null) {
                    window = DEFAULTS.window();
                }
                if (window.isNegative() || window.isZero()) {
                    throw new IllegalArgumentException(
                            "alpha.risk.frequency.window must be positive, got " + window
                                    + ": a window that never slides would count the first "
                                    + maxOrders + " orders of the process and block everything after");
                }
            }
        }

        /**
         * 仓位换算 (FR-RK-07). {@code targetExposure} stays null when unset and the wiring
         * substitutes {@code PositionSizer.Policy.DEFAULT}, so the number has one definition - it is
         * not repeated in yml where the two could drift apart.
         *
         * <p>It lives here rather than under {@code alpha.backtest}: how much of the account one
         * signal may commit is a risk parameter, and paper/live must size exactly as the backtest
         * did or the backtest is not evidence about them (FR-BT-06).
         */
        @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
        public record Sizing(BigDecimal targetExposure) {

            public static final Sizing DEFAULTS = new Sizing(null);

            public Sizing {
                if (targetExposure != null) {
                    require("sizing.target-exposure", targetExposure, BigDecimal.ZERO, BigDecimal.ONE);
                }
            }

            /** The exposure the shipped configuration actually runs with. */
            public BigDecimal effectiveTargetExposure() {
                return targetExposure == null
                        ? PositionSizer.Policy.DEFAULT.targetExposure()
                        : targetExposure;
            }
        }

        public Risk {
            if (account == null) {
                account = Account.DEFAULTS;
            }
            if (order == null) {
                order = Order.DEFAULTS;
            }
            if (portfolio == null) {
                portfolio = Portfolio.DEFAULTS;
            }
            if (breaker == null) {
                breaker = Breaker.DEFAULTS;
            }
            if (frequency == null) {
                frequency = Frequency.DEFAULTS;
            }
            if (sizing == null) {
                sizing = Sizing.DEFAULTS;
            }
            // The one inequality that spans two blocks (取舍 17). A flip is one order of
            // 2 x targetExposure, so a cap tighter than that blocks every flip and every entry to
            // target: the account keeps holding what the strategy already reversed out of, and the
            // only symptom is a stream of alerts that read like a bug in the rule.
            BigDecimal exposure = sizing.effectiveTargetExposure();
            BigDecimal flipNotional = exposure.multiply(BigDecimal.valueOf(2));
            if (order.maxNotionalFraction().compareTo(flipNotional) < 0) {
                throw new IllegalArgumentException("alpha.risk.order.max-notional-fraction ("
                        + order.maxNotionalFraction() + ") must be at least twice the target exposure ("
                        + exposure + " -> " + flipNotional + "): the sizer turns a flip into a single"
                        + " order of 2 x targetExposure, so a tighter cap blocks every flip and every"
                        + " entry to target while the account keeps the position the strategy reversed");
            }
        }
    }

    /**
     * One {@code alpha.strategies[]} entry (FR-ST-01). {@code symbols} and {@code interval}
     * fall back to the global ones; {@code enabled} defaults to true - listing a strategy
     * means wanting to run it.
     */
    public record StrategyEntry(
            String id,
            String type,
            Boolean enabled,
            List<String> symbols,
            String interval,
            Map<String, String> params) {
    }

    /**
     * The {@code alpha.backtest} block (T219). Only what is genuinely backtest-specific lives
     * here: which stored bars to replay, over what range, at what simulated cost, and where the
     * report goes.
     *
     * <p>Starting equity and the strategy list deliberately do <em>not</em> move here. They are
     * {@code alpha.initial-cash} and {@code alpha.strategies} - the same knobs paper and live
     * read. A backtest that could be configured with a different book or a different strategy set
     * than the live system would stop being evidence about live trading (FR-BT-06, FR-ST-01).
     *
     * <p>{@code cost} and {@code quiescenceTimeout} stay null when unset rather than defaulting
     * here: their real defaults live in {@code SimulatedExecutor.CostModel} and {@code EventEngine},
     * and restating those numbers in a yml binding is how two definitions of one constant drift
     * apart. The wiring substitutes the domain default, per field for {@code cost} so a partial
     * override works. Target exposure moved to {@code alpha.risk.sizing} - it sizes an order in
     * every mode, so a backtest-only knob for it would let the modes disagree (FR-BT-06).
     */
    public record Backtest(
            Data data,
            Instant from,
            Instant to,
            List<SeriesEntry> series,
            Cost cost,
            List<RuleEntry> rules,
            Path reportDir,
            boolean journal,
            Duration quiescenceTimeout) {

        /** What an absent block means: no range, so the wiring refuses rather than inventing one. */
        public static final Backtest DEFAULTS =
                new Backtest(null, null, null, null, null, null, null, false, null);

        /**
         * {@code csv} reads one file per symbol+interval under {@code csvDir}; {@code db} reads the
         * business database at {@code jdbcUrl} (SQLite for local dev, DESIGN §10). Both implement
         * the same {@code KlineRepository} contract, so nothing downstream can tell them apart.
         */
        public record Data(String source, Path csvDir, String jdbcUrl) {

            public Data {
                if (source == null || source.isBlank()) {
                    source = "csv";
                }
                if (csvDir == null) {
                    csvDir = Path.of("data", "klines");
                }
            }
        }

        /**
         * One series to replay. When the list is empty the wiring derives it from the enabled
         * strategies instead, which is the honest default: replaying a series no strategy listens
         * to only costs time, and omitting one a strategy needs means it silently never trades.
         */
        public record SeriesEntry(String symbol, String interval) {
        }

        /** Partial override of the simulated cost model; each null falls back per field. */
        public record Cost(
                BigDecimal takerFeeRate,
                BigDecimal fixedSlippageBps,
                BigDecimal amplitudeFactor,
                BigDecimal fundingRatePerInterval) {
        }

        /**
         * Precision constraints for one symbol. Live and paper fetch these from exchangeInfo;
         * backtest has no exchange to ask, so they are declared here (FR-GW-03). A symbol a
         * strategy trades and no rule covers stops the run - see {@code BacktestRunner.Config}.
         */
        public record RuleEntry(
                String symbol,
                BigDecimal tickSize,
                BigDecimal stepSize,
                BigDecimal minNotional) {
        }

        public Backtest {
            if (data == null) {
                data = new Data(null, null, null);
            }
            if (series == null) {
                series = List.of();
            }
            if (rules == null) {
                rules = List.of();
            }
            if (reportDir == null) {
                reportDir = Path.of("reports");
            }
        }
    }

    public AlphaProperties {
        if (symbols == null || symbols.isEmpty()) {
            symbols = List.of("BTCUSDT.PERP");
        }
        if (interval == null || interval.isBlank()) {
            interval = "1h";
        }
        if (journalDir == null) {
            journalDir = Path.of("logs");
        }
        if (trading == null) {
            trading = new Trading(false);
        }
        if (initialCash == null) {
            initialCash = new BigDecimal("10000");
        }
        if (initialCash.signum() <= 0) {
            throw new IllegalArgumentException("alpha.initial-cash must be positive, got " + initialCash);
        }
        if (risk == null) {
            risk = Risk.DEFAULTS;
        }
        if (strategies == null) {
            strategies = List.of();
        }
        if (backtest == null) {
            backtest = Backtest.DEFAULTS;
        }
        if (download == null) {
            download = Download.DEFAULTS;
        }
        if (reconciliation == null) {
            reconciliation = Reconciliation.DEFAULTS;
        }
    }

    /**
     * One limit, checked where it is declared rather than in a validator class: a fraction outside
     * its range is a rule that never fires (too high) or fires on everything (too low), and both
     * read as a strategy problem rather than a typo.
     *
     * <p><b>Why this lives on {@code AlphaProperties} and not on {@code Risk}.</b> Every
     * {@code Risk.*} level calls it from its own compact constructor. While it lived in {@code Risk},
     * that call triggered {@code Risk.<clinit>}, which builds {@code Risk.DEFAULTS} by reading the
     * nested {@code DEFAULTS} - and if a level was mid-initialization at that moment its
     * {@code DEFAULTS} was still null, so {@code Risk.DEFAULTS} captured a null block. Whether that
     * happened depended only on which class the JVM initialized first, so the same test passed or
     * NPE'd on run order. {@code AlphaProperties} has no static fields, so its {@code <clinit>} is
     * trivial and the callback cannot re-enter the cycle.
     *
     * @param exclusiveMax when non-null the value must be &lt;= it; a deviation or a loss fraction
     *                     above 1 would mean "no limit at all"
     */
    private static void require(String key, BigDecimal value, BigDecimal exclusiveMin, BigDecimal exclusiveMax) {
        if (value.compareTo(exclusiveMin) <= 0) {
            throw new IllegalArgumentException("alpha.risk." + key + " must be > "
                    + exclusiveMin.toPlainString() + ", got " + value.toPlainString());
        }
        if (exclusiveMax != null && value.compareTo(exclusiveMax) > 0) {
            throw new IllegalArgumentException("alpha.risk." + key + " must be <= "
                    + exclusiveMax.toPlainString() + ", got " + value.toPlainString());
        }
    }
}
