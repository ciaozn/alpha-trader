package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.portfolio.Position;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything one finished backtest has to say, bundled so the renderers take a single argument
 * and so nothing can be quietly dropped between the run and the report (FR-BT-04).
 *
 * <p>Deliberately free of anything observed at render time - no wall-clock "generated at", no
 * host name, no JVM version. SC-02 asks that the same input run three times be bit-identical,
 * and a report stamped with {@code Instant.now()} fails that while looking perfectly reasonable.
 * Every time in here is business time taken from the replay itself.
 *
 * <p>The unfinished business is part of the report rather than an aside: {@link #pendingOrders}
 * are signals whose fill never arrived because the data ran out, {@link #rejections} are orders
 * the simulated exchange refused, and {@code replay.gaps()} are holes in the input. Omitting any
 * of them would let a run that silently did nothing look like a run that traded and broke even.
 */
public record BacktestReport(
        BacktestDataFeeder.ReplaySummary replay,
        List<BacktestDataFeeder.Series> series,
        PerformanceMetrics metrics,
        EquityCurve curve,
        List<Trade> trades,
        List<SimulatedExecutor.SimulatedFill> fills,
        List<SimulatedExecutor.FundingSettlement> funding,
        List<SimulatedExecutor.Rejection> rejections,
        List<OrderRequestEvent> pendingOrders,
        List<Position> openPositions,
        SimulatedExecutor.CostModel costModel) {

    public BacktestReport {
        series = List.copyOf(series);
        trades = List.copyOf(trades);
        fills = List.copyOf(fills);
        funding = List.copyOf(funding);
        rejections = List.copyOf(rejections);
        pendingOrders = List.copyOf(pendingOrders);
        openPositions = List.copyOf(openPositions);
    }

    /** Total funding charged over the run; the sum of the settlements, not a book reading. */
    public BigDecimal fundingTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (SimulatedExecutor.FundingSettlement settlement : funding) {
            total = total.add(settlement.charge());
        }
        return total;
    }

    /**
     * True when the run left something unresolved. Reported loudly rather than buried: each of
     * these means a number in the report is describing less than the whole picture.
     */
    public boolean hasUnresolved() {
        return !pendingOrders.isEmpty() || !rejections.isEmpty() || !openPositions.isEmpty()
                || metrics.openTrades() > 0 || replay.hasGaps();
    }
}
