package com.ciaozn.alphatrader.backtest;

/**
 * Placeholder for the P2 backtest assembly - historical data feeder (VirtualClock),
 * simulated matcher (signal at close, fill at next open, slippage + fees hardcoded
 * as the anti-look-ahead discipline, FR-BT-02) and HTML performance report.
 * Implemented in plan phase P2.
 */
public final class BacktestRunner {

    private BacktestRunner() {
        throw new UnsupportedOperationException("Backtest is implemented in P2");
    }
}
