package com.ciaozn.alphatrader.strategy.builtin;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyContext;
import com.ciaozn.alphatrader.strategy.indicator.Sma;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dual moving-average crossover (FR-ST-02): a golden cross goes long, a death cross flattens
 * or flips short depending on {@code allowShort}.
 *
 * <p>Signals fire on the CROSSING bar only, never on every bar where fast is above slow.
 * That is what keeps one market move to one position change, and it makes the strategy
 * idempotent under a re-delivered bar (the window is unchanged, so no new cross is seen).
 *
 * <p>State is per symbol and lives in fields: the same instance runs over the whole history
 * in backtest and over the live stream in paper/live, which is exactly the isomorphism the
 * project depends on (FR-BT-06).
 */
public final class MaCrossStrategy implements Strategy {

    public static final String TYPE = "ma-cross";

    /**
     * @param fastPeriod fast SMA length
     * @param slowPeriod slow SMA length, must be longer than the fast one
     * @param allowShort false turns a death cross into FLAT instead of SHORT
     */
    public record Params(int fastPeriod, int slowPeriod, boolean allowShort) {

        public static final Params DEFAULT = new Params(10, 30, true);

        public Params {
            if (fastPeriod < 1) {
                throw new IllegalArgumentException("fastPeriod must be >= 1, got " + fastPeriod);
            }
            if (slowPeriod <= fastPeriod) {
                throw new IllegalArgumentException(
                        "slowPeriod (" + slowPeriod + ") must be > fastPeriod (" + fastPeriod + ")");
            }
        }
    }

    private static final class CrossState {
        private final Sma fast;
        private final Sma slow;
        private double previousFast = Double.NaN;
        private double previousSlow = Double.NaN;

        private CrossState(Params params) {
            this.fast = new Sma(params.fastPeriod());
            this.slow = new Sma(params.slowPeriod());
        }
    }

    private final String id;
    private final Interval interval;
    private final Set<Symbol> symbols;
    private final Params params;
    private final Map<Symbol, CrossState> states = new LinkedHashMap<>();

    public MaCrossStrategy(String id, Interval interval, Set<Symbol> symbols, Params params) {
        this.id = id;
        this.interval = interval;
        this.symbols = Collections.unmodifiableSet(new LinkedHashSet<>(symbols));
        this.params = params;
        if (this.symbols.isEmpty()) {
            throw new IllegalArgumentException("Strategy " + id + " has no symbols");
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<Symbol> symbols() {
        return symbols;
    }

    @Override
    public Interval interval() {
        return interval;
    }

    @Override
    public void onKline(KlineEvent event, StrategyContext context) {
        CrossState state = states.computeIfAbsent(event.symbol(), symbol -> new CrossState(params));
        double close = event.kline().close().doubleValue();
        state.fast.update(close);
        state.slow.update(close);
        if (!state.fast.ready() || !state.slow.ready()) {
            return;
        }

        double fast = state.fast.value();
        double slow = state.slow.value();
        double previousFast = state.previousFast;
        double previousSlow = state.previousSlow;
        state.previousFast = fast;
        state.previousSlow = slow;
        // Detecting a crossing needs two consecutive settled values; the first ready bar
        // only establishes the baseline.
        if (Double.isNaN(previousFast) || Double.isNaN(previousSlow)) {
            return;
        }

        if (previousFast <= previousSlow && fast > slow) {
            context.emit(event.symbol(), Direction.LONG, 1.0, reason("golden cross", fast, slow));
        } else if (previousFast >= previousSlow && fast < slow) {
            Direction direction = params.allowShort() ? Direction.SHORT : Direction.FLAT;
            context.emit(event.symbol(), direction, 1.0, reason("death cross", fast, slow));
        }
    }

    private String reason(String cross, double fast, double slow) {
        return String.format(Locale.ROOT, "%s: SMA%d=%.4f vs SMA%d=%.4f",
                cross, params.fastPeriod(), fast, params.slowPeriod(), slow);
    }
}
