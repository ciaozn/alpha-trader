package com.ciaozn.alphatrader.app.config;

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
        List<StrategyEntry> strategies,
        Backtest backtest) {

    public record Trading(boolean enabled) {
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
     * <p>{@code exposure}, {@code cost} and {@code quiescenceTimeout} stay null when unset rather
     * than defaulting here: their real defaults live in {@code PositionSizer.Policy},
     * {@code SimulatedExecutor.CostModel} and {@code EventEngine}, and restating those numbers in
     * a yml binding is how two definitions of one constant drift apart. The wiring substitutes the
     * domain default, per field for {@code cost} so a partial override works.
     */
    public record Backtest(
            Data data,
            Instant from,
            Instant to,
            List<SeriesEntry> series,
            BigDecimal exposure,
            Cost cost,
            List<RuleEntry> rules,
            Path reportDir,
            boolean journal,
            Duration quiescenceTimeout) {

        /** What an absent block means: no range, so the wiring refuses rather than inventing one. */
        public static final Backtest DEFAULTS =
                new Backtest(null, null, null, null, null, null, null, null, false, null);

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
        if (strategies == null) {
            strategies = List.of();
        }
        if (backtest == null) {
            backtest = Backtest.DEFAULTS;
        }
    }
}
