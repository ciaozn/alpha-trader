package com.ciaozn.alphatrader.execution;

import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.TickerEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MarkPriceUpdaterTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");

    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final MarkPriceUpdater updater = new MarkPriceUpdater(portfolio);

    private static KlineEvent bar(boolean closed, String close) {
        Kline kline = new Kline(1000L, new BigDecimal("50000"), new BigDecimal("51000"),
                new BigDecimal("49000"), new BigDecimal(close), new BigDecimal("10"), 2000L);
        return KlineEvent.of(BTC, Interval.H1, kline, closed, 2000L);
    }

    @Test
    void closedBarsMarkTheBook() {
        updater.onEvent(bar(true, "50500.5"), event -> {
        });
        assertThat(portfolio.markOf(BTC)).isEqualByComparingTo(new BigDecimal("50500.5"));
    }

    @Test
    void inProgressBarsDoNot() {
        // An open bar's close is still moving; equity sampled from it would not be reproducible
        // between two runs (NFR-04), and risk rules would be checked against a number that changes
        // every tick.
        updater.onEvent(bar(false, "50500.5"), event -> {
        });
        assertThat(portfolio.markOf(BTC)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void tickersMarkUnconditionally() {
        updater.onEvent(TickerEvent.of(BTC, new BigDecimal("51234.5"), 3000L), event -> {
        });
        assertThat(portfolio.markOf(BTC)).isEqualByComparingTo(new BigDecimal("51234.5"));
    }

    @Test
    void marksFeedEquity() {
        portfolio.applyFill(BTC, com.ciaozn.alphatrader.common.model.Side.BUY,
                new BigDecimal("50000"), new BigDecimal("1"), BigDecimal.ZERO);
        updater.onEvent(bar(true, "51000"), event -> {
        });
        // Without a mark, the position would be valued at its entry price and unrealized P&L would
        // read zero - the account-level rules would then be checked against a stale number.
        assertThat(portfolio.equity()).isEqualByComparingTo(new BigDecimal("11000"));
    }
}
