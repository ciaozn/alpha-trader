package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.TickerEvent;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;

/**
 * Keeps the book's mark prices current from market data (T319 precondition).
 *
 * <p>Nothing else in the online path can: the OMS only learns prices from executions, and a price
 * from a fill is the price of that fill, not the price of the world. Every risk rule, the sizer and
 * the equity curve read {@code Portfolio.markOf}, so without this the account's equity would be
 * frozen between trades and rules would be checked against a stale number - the account-level rules
 * would pass or fail on data from the last fill rather than from the market.
 *
 * <p>Only closed bars mark the book for k-line data: an in-progress bar's close is a moving number,
 * and equity sampled from it is not comparable between two runs (NFR-04). Ticker data marks
 * unconditionally - a ticker is a price, not a half-finished bar.
 */
public final class MarkPriceUpdater implements EventHandler {

    private final Portfolio portfolio;

    public MarkPriceUpdater(Portfolio portfolio) {
        this.portfolio = portfolio;
    }

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        switch (event) {
            case KlineEvent kline -> {
                if (kline.closed()) {
                    portfolio.mark(kline.symbol(), kline.kline().close());
                }
            }
            case TickerEvent ticker -> portfolio.mark(ticker.symbol(), ticker.price());
            default -> {
            }
        }
    }
}
