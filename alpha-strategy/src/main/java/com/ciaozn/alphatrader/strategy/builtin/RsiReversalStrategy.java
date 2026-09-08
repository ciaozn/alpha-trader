package com.ciaozn.alphatrader.strategy.builtin;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Position;
import com.ciaozn.alphatrader.strategy.Strategy;
import com.ciaozn.alphatrader.strategy.StrategyContext;
import com.ciaozn.alphatrader.strategy.indicator.Atr;
import com.ciaozn.alphatrader.strategy.indicator.Rsi;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RSI overbought/oversold reversal (FR-ST-03): RSI below the oversold threshold goes long,
 * RSI above the overbought threshold flattens the long. Long-only by design - "buy fear,
 * exit greed" has no short leg in this strategy.
 *
 * <p>Signals are emitted on transitions of the desired state, not on every bar that satisfies
 * the condition. Without that, a market sitting below RSI 30 for six bars would produce six
 * signals and six orders for one idea. The desired state also covers the window between an
 * emitted signal and its fill, when the portfolio still reads flat.
 *
 * <p>Known limitation (accepted in P2): if the risk layer drops a signal - for example the
 * sized quantity is below the exchange minimum - the strategy does not retry it. It waits for
 * the opposite condition. Retry-on-reject belongs to the OMS in P3.
 */
public final class RsiReversalStrategy implements Strategy {

    public static final String TYPE = "rsi-reversal";

    /**
     * @param period         RSI period
     * @param oversold       buy threshold, e.g. 30
     * @param overbought     exit threshold, e.g. 70
     * @param atrPeriod      ATR period for the optional volatility filter
     * @param minAtrPercent  minimum ATR as a percentage of price required to open a position;
     *                       0 disables the filter. Guards against entering dead markets where
     *                       the RSI signal is mostly noise and fees dominate.
     */
    public record Params(int period, double oversold, double overbought, int atrPeriod, double minAtrPercent) {

        public static final Params DEFAULT = new Params(14, 30, 70, 14, 0);

        public Params {
            if (period < 2) {
                throw new IllegalArgumentException("RSI period must be >= 2, got " + period);
            }
            if (!(oversold > 0 && oversold < overbought && overbought < 100)) {
                throw new IllegalArgumentException(
                        "Requires 0 < oversold (" + oversold + ") < overbought (" + overbought + ") < 100");
            }
            if (atrPeriod < 1) {
                throw new IllegalArgumentException("ATR period must be >= 1, got " + atrPeriod);
            }
            if (minAtrPercent < 0) {
                throw new IllegalArgumentException("minAtrPercent must be >= 0, got " + minAtrPercent);
            }
        }

        public boolean volatilityFilterEnabled() {
            return minAtrPercent > 0;
        }
    }

    private static final class ReversalState {
        private final Rsi rsi;
        private final Atr atr;
        private Direction desired = Direction.FLAT;
        /** Signed quantity a FLAT signal was already emitted for; null means none pending. */
        private BigDecimal exitRequestedQty;

        private ReversalState(Params params) {
            this.rsi = new Rsi(params.period());
            this.atr = new Atr(params.atrPeriod());
        }
    }

    private final String id;
    private final Interval interval;
    private final Set<Symbol> symbols;
    private final Params params;
    private final Map<Symbol, ReversalState> states = new LinkedHashMap<>();

    public RsiReversalStrategy(String id, Interval interval, Set<Symbol> symbols, Params params) {
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
        ReversalState state = states.computeIfAbsent(event.symbol(), symbol -> new ReversalState(params));
        state.rsi.update(event.kline().close().doubleValue());
        state.atr.update(event.kline().high().doubleValue(),
                event.kline().low().doubleValue(),
                event.kline().close().doubleValue());
        if (!state.rsi.ready()) {
            return;
        }

        double rsi = state.rsi.value();
        double close = event.kline().close().doubleValue();

        if (rsi < params.oversold() && state.desired == Direction.FLAT && volatilityAllows(state, close)) {
            state.desired = Direction.LONG;
            state.exitRequestedQty = null;
            context.emit(event.symbol(), Direction.LONG, 1.0,
                    String.format(Locale.ROOT, "RSI%d=%.2f below oversold %.1f%s",
                            params.period(), rsi, params.oversold(), filterNote(state, close)));
            return;
        }

        // The portfolio check catches a long that exists without our desired state (P3 reconciliation).
        Position position = context.portfolio().position(event.symbol());
        boolean holdingLong = position.direction() == Direction.LONG;
        // Keying the exit on the held quantity means one FLAT per position, not one per bar: an
        // overbought market that lasts six bars orders a single exit, while a late fill that
        // re-creates the long after a FLAT was already emitted still gets exited.
        if (rsi > params.overbought() && (state.desired == Direction.LONG || holdingLong)
                && !sameQty(state.exitRequestedQty, position.signedQty())) {
            state.desired = Direction.FLAT;
            state.exitRequestedQty = position.signedQty();
            context.emit(event.symbol(), Direction.FLAT, 1.0,
                    String.format(Locale.ROOT, "RSI%d=%.2f above overbought %.1f, closing long",
                            params.period(), rsi, params.overbought()));
        }
    }

    private static boolean sameQty(BigDecimal requested, BigDecimal held) {
        return requested != null && requested.compareTo(held) == 0;
    }

    private boolean volatilityAllows(ReversalState state, double close) {
        if (!params.volatilityFilterEnabled()) {
            return true;
        }
        return state.atr.ready() && state.atr.percentOf(close) >= params.minAtrPercent();
    }

    private String filterNote(ReversalState state, double close) {
        if (!params.volatilityFilterEnabled()) {
            return "";
        }
        return String.format(Locale.ROOT, ", ATR%d=%.3f%% of price",
                params.atrPeriod(), state.atr.percentOf(close));
    }
}
