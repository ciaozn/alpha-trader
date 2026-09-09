package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Samples {@link Portfolio#equity()} once per closed replay round and collects the
 * {@link EquityCurve} the metrics and the report are built from.
 *
 * <p>Wired in as the feeder's {@link BacktestDataFeeder.RoundListener} rather than as an
 * event handler: the sample has to be taken after the bar's whole cascade has closed, and
 * that moment is only observable from the replay loop. Sampled any earlier the point would
 * miss the fills that bar just produced; sampled by a handler registered "last" it would be
 * correct only until someone registers one more handler.
 *
 * <p>In live the same sampler is driven by a timer instead of a replay loop - {@link #sample}
 * is the only entry point either path needs.
 */
public final class EquityRecorder implements BacktestDataFeeder.RoundListener {

    private final Portfolio portfolio;
    private final List<EquityCurve.Point> points = new ArrayList<>();
    private long rounds;

    public EquityRecorder(Portfolio portfolio) {
        this.portfolio = portfolio;
    }

    @Override
    public void afterRound(long businessTs) {
        sample(businessTs);
    }

    /**
     * Records the current equity as of {@code businessTs}. A second sample at an instant
     * already recorded replaces the first: two series closing together produce two rounds with
     * the same timestamp, and equity as of that instant means after both.
     */
    public void sample(long businessTs) {
        rounds++;
        BigDecimal equity = portfolio.equity();
        int last = points.size() - 1;
        if (last >= 0 && points.get(last).businessTs() == businessTs) {
            points.set(last, new EquityCurve.Point(businessTs, equity));
            return;
        }
        points.add(new EquityCurve.Point(businessTs, equity));
    }

    public EquityCurve curve() {
        return new EquityCurve(portfolio.startingEquity(), points);
    }

    /** Rounds sampled, which exceeds the point count whenever series closed at the same instant. */
    public long rounds() {
        return rounds;
    }
}
