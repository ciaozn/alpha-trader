package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeTrackerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_006_400_000L;
    private static final long HOUR = 3_600_000L;

    private Portfolio portfolio;
    private TradeTracker tracker;
    private int sequence;

    @BeforeEach
    void setUp() {
        portfolio = new Portfolio(new BigDecimal("100000"));
        tracker = new TradeTracker(portfolio);
    }

    /**
     * Applies one execution to the book and to the tracker, the way the simulated executor does
     * (book first, then the event), and marks the symbol at the fill price so an open position
     * has a value.
     */
    private void fill(Symbol symbol, Side side, String price, String qty, String fee, long ts) {
        BigDecimal fillPrice = new BigDecimal(price);
        BigDecimal fillQty = new BigDecimal(qty);
        BigDecimal fillFee = new BigDecimal(fee);
        portfolio.applyFill(symbol, side, fillPrice, fillQty, fillFee);
        portfolio.mark(symbol, fillPrice);
        tracker.onEvent(FillEvent.of("coid-" + (++sequence), symbol, side, fillPrice, fillQty, fillFee, ts),
                event -> {
                });
        // The tracker derives its own running quantity from the fills it sees; if it ever
        // disagreed with the book, the report would describe positions the account does not have.
        assertThat(signedQtyOf(symbol))
                .as("tracker agrees with the book after %s %s %s", side, qty, symbol.unified())
                .isEqualByComparingTo(portfolio.position(symbol).signedQty());
    }

    /** The tracker's signed quantity for a symbol, rebuilt from its open-trade view. */
    private BigDecimal signedQtyOf(Symbol symbol) {
        return tracker.openTrades().stream()
                .filter(trade -> trade.symbol().equals(symbol))
                .map(trade -> trade.direction() == Trade.Direction.LONG
                        ? trade.openQty()
                        : trade.openQty().negate())
                .reduce(Money.zero(), BigDecimal::add);
    }

    private static BigDecimal money(String value) {
        return Money.of(value);
    }

    @Test
    void aRoundTripIsOneTradeNotOneTradePerFill() {
        fill(BTC, Side.BUY, "100", "1", "0.50", T0);
        fill(BTC, Side.SELL, "110", "1", "0.55", T0 + HOUR);

        List<Trade> trades = tracker.trades();
        assertThat(trades).hasSize(1);
        Trade trade = trades.get(0);
        assertThat(trade.open()).isFalse();
        assertThat(trade.direction()).isEqualTo(Trade.Direction.LONG);
        assertThat(trade.openedQty()).isEqualByComparingTo("1");
        assertThat(trade.closedQty()).isEqualByComparingTo("1");
        assertThat(trade.avgEntryPrice()).isEqualByComparingTo("100");
        assertThat(trade.avgExitPrice()).isEqualByComparingTo("110");
        assertThat(trade.grossPnl()).isEqualByComparingTo("10");
        assertThat(trade.fees()).isEqualByComparingTo("1.05");
        assertThat(trade.netPnl()).isEqualByComparingTo("8.95");
        assertThat(trade.openedTs()).isEqualTo(T0);
        assertThat(trade.closedTs()).isEqualTo(T0 + HOUR);
        assertThat(trade.holdingMillis()).isEqualTo(HOUR);
        assertThat(tracker.fillsTracked()).isEqualTo(2);
    }

    @Test
    void addsAndPartialClosesStayInOneTradeUntilThePositionFlattens() {
        fill(BTC, Side.BUY, "100", "2", "1.00", T0);
        fill(BTC, Side.SELL, "110", "1", "0.55", T0 + HOUR);

        assertThat(tracker.closedTrades()).as("still holding one unit").isEmpty();
        Trade open = tracker.openTrades().get(0);
        assertThat(open.openQty()).isEqualByComparingTo("1");
        // 10 realized on the unit sold at 110, plus 10 unrealized on the unit still held there
        assertThat(open.grossPnl()).isEqualByComparingTo("20");

        fill(BTC, Side.SELL, "120", "1", "0.60", T0 + 2 * HOUR);

        assertThat(tracker.openTrades()).isEmpty();
        Trade closed = tracker.closedTrades().get(0);
        assertThat(closed.openedQty()).isEqualByComparingTo("2");
        assertThat(closed.closedQty()).isEqualByComparingTo("2");
        assertThat(closed.avgEntryPrice()).isEqualByComparingTo("100");
        assertThat(closed.avgExitPrice()).as("(110 + 120) / 2").isEqualByComparingTo("115");
        assertThat(closed.grossPnl()).isEqualByComparingTo("30");
        assertThat(closed.netPnl()).isEqualByComparingTo("27.85");
        assertThat(closed.closedTs()).isEqualTo(T0 + 2 * HOUR);
    }

    @Test
    void aFlipEndsOneTradeAndStartsAnotherWithTheFeeSplitSoTheHalvesSumExactly() {
        fill(BTC, Side.SELL, "100", "1", "0.00", T0);
        // one fill closes the short and opens a long; 1.00 over a 1:2 split does not divide evenly
        fill(BTC, Side.BUY, "110", "3", "1.00", T0 + HOUR);

        assertThat(tracker.closedTrades()).hasSize(1);
        Trade closedShort = tracker.closedTrades().get(0);
        assertThat(closedShort.direction()).isEqualTo(Trade.Direction.SHORT);
        assertThat(closedShort.grossPnl()).as("sold at 100, covered at 110").isEqualByComparingTo("-10");
        assertThat(closedShort.fees()).isEqualByComparingTo("0.33333333");
        assertThat(closedShort.netPnl()).isEqualByComparingTo("-10.33333333");
        assertThat(closedShort.closedTs()).isEqualTo(T0 + HOUR);

        assertThat(tracker.openTrades()).hasSize(1);
        Trade openLong = tracker.openTrades().get(0);
        assertThat(openLong.direction()).isEqualTo(Trade.Direction.LONG);
        assertThat(openLong.openedTs()).as("the new trade starts on the flip, not on the short").isEqualTo(T0 + HOUR);
        assertThat(openLong.openQty()).isEqualByComparingTo("2");
        assertThat(openLong.avgEntryPrice()).isEqualByComparingTo("110");
        assertThat(openLong.fees()).isEqualByComparingTo("0.66666667");

        // the point of deriving the second half instead of prorating both: what the exchange
        // charged is what the two trades report, to the satoshi
        assertThat(closedShort.fees().add(openLong.fees())).isEqualTo(money("1.00"));
        assertThat(tracker.trades().get(0).direction()).as("oldest first").isEqualTo(Trade.Direction.SHORT);
    }

    @Test
    void aShortRoundTripProfitsWhenThePriceFalls() {
        fill(BTC, Side.SELL, "100", "1", "0.50", T0);
        fill(BTC, Side.BUY, "90", "1", "0.45", T0 + HOUR);

        Trade trade = tracker.closedTrades().get(0);
        assertThat(trade.direction()).isEqualTo(Trade.Direction.SHORT);
        assertThat(trade.grossPnl()).isEqualByComparingTo("10");
        assertThat(trade.netPnl()).isEqualByComparingTo("9.05");
        assertThat(trade.isWin()).isTrue();
    }

    @Test
    void theTradeStillOpenAtTheEndIsValuedAtTheLastMark() {
        fill(BTC, Side.BUY, "100", "2", "1.00", T0);
        portfolio.mark(BTC, new BigDecimal("130"));

        Trade open = tracker.openTrades().get(0);
        assertThat(open.open()).isTrue();
        assertThat(open.closedQty()).isEqualByComparingTo("0");
        assertThat(open.avgExitPrice()).as("nothing was ever sold").isEqualByComparingTo("0");
        assertThat(open.grossPnl()).as("2 units marked at 130 against 200 paid").isEqualByComparingTo("60");
        assertThat(open.netPnl()).isEqualByComparingTo("59");
        assertThat(open.closedTs()).as("an open trade reports its last fill, not an exit").isEqualTo(T0);
        assertThat(tracker.closedTrades()).isEmpty();
    }

    @Test
    void anOpenTradeFollowsTheMarkBothWays() {
        fill(BTC, Side.SELL, "100", "1", "0.00", T0);
        portfolio.mark(BTC, new BigDecimal("80"));
        assertThat(tracker.openTrades().get(0).grossPnl()).isEqualByComparingTo("20");

        portfolio.mark(BTC, new BigDecimal("130"));
        assertThat(tracker.openTrades().get(0).grossPnl()).isEqualByComparingTo("-30");
        assertThat(tracker.closedTrades()).as("re-marking never closes anything").isEmpty();
    }

    /**
     * The invariant that justifies computing trade P&L from cash flows instead of reading the
     * book's realized figure: over a sequence with adds, partial closes and a flip on two
     * symbols, the trades must account for exactly what the account shows.
     */
    @Test
    void theTradesAccountForExactlyWhatTheBookShows() {
        fill(BTC, Side.BUY, "100", "2", "1.00", T0);
        fill(BTC, Side.SELL, "120", "1", "0.60", T0 + HOUR);
        fill(BTC, Side.BUY, "90", "1", "0.45", T0 + 2 * HOUR);
        fill(BTC, Side.SELL, "110", "2", "1.10", T0 + 3 * HOUR);
        fill(ETH, Side.SELL, "200", "3", "3.00", T0 + 4 * HOUR);
        fill(ETH, Side.BUY, "180", "1", "0.90", T0 + 5 * HOUR);
        fill(ETH, Side.BUY, "210", "3", "1.00", T0 + 6 * HOUR);

        List<Trade> trades = tracker.trades();
        BigDecimal gross = trades.stream().map(Trade::grossPnl).reduce(Money.zero(), BigDecimal::add);
        BigDecimal fees = trades.stream().map(Trade::fees).reduce(Money.zero(), BigDecimal::add);
        BigDecimal net = trades.stream().map(Trade::netPnl).reduce(Money.zero(), BigDecimal::add);

        assertThat(gross).isEqualByComparingTo(portfolio.realizedPnl().add(portfolio.unrealizedPnl()));
        assertThat(fees).isEqualByComparingTo(portfolio.feeTotal());
        // no funding in this sequence, so the trades' net P&L is the whole move in equity
        assertThat(net).isEqualByComparingTo(portfolio.equity().subtract(portfolio.startingEquity()));
        assertThat(gross).isEqualByComparingTo("50");
        assertThat(fees).isEqualByComparingTo("8.05");
    }

    @Test
    void eachSymbolGetsItsOwnTradeAndTheyAreReportedOldestFirst() {
        fill(ETH, Side.BUY, "2000", "1", "1.00", T0);
        fill(BTC, Side.BUY, "100", "1", "1.00", T0 + HOUR);
        fill(ETH, Side.SELL, "2100", "1", "1.00", T0 + 2 * HOUR);

        assertThat(tracker.closedTrades()).hasSize(1);
        assertThat(tracker.openTrades()).hasSize(1);
        assertThat(tracker.trades())
                .extracting(trade -> trade.symbol().unified())
                .containsExactly("ETHUSDT.PERP", "BTCUSDT.PERP");
    }

    @Test
    void twoTradesOnTheSameSymbolAreSeparateOnceThePositionFlattens() {
        fill(BTC, Side.BUY, "100", "1", "0.10", T0);
        fill(BTC, Side.SELL, "110", "1", "0.10", T0 + HOUR);
        fill(BTC, Side.BUY, "105", "1", "0.10", T0 + 2 * HOUR);
        fill(BTC, Side.SELL, "108", "1", "0.10", T0 + 3 * HOUR);

        assertThat(tracker.closedTrades()).hasSize(2);
        assertThat(tracker.closedTrades())
                .extracting(Trade::netPnl)
                .containsExactly(money("9.80"), money("2.80"));
        assertThat(tracker.closedTrades())
                .extracting(Trade::openedTs)
                .containsExactly(T0, T0 + 2 * HOUR);
    }

    @Test
    void aFillWithANonPositiveQuantityIsRefused() {
        assertThatThrownBy(() -> tracker.onEvent(
                FillEvent.of("coid", BTC, Side.BUY, new BigDecimal("100"), BigDecimal.ZERO,
                        BigDecimal.ZERO, T0),
                event -> {
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be > 0");
        assertThat(tracker.trades()).isEmpty();
        assertThat(tracker.fillsTracked()).isZero();
    }

    @Test
    void anythingThatIsNotAFillLeavesTheTradesAlone() {
        BigDecimal price = new BigDecimal("100");
        Kline kline = new Kline(T0, price, price, price, price, BigDecimal.ONE, T0 + HOUR - 1);
        tracker.onEvent(KlineEvent.of(BTC, Interval.H1, kline, true, T0), event -> {
        });

        assertThat(tracker.trades()).isEmpty();
        assertThat(tracker.fillsTracked()).isZero();
    }
}
