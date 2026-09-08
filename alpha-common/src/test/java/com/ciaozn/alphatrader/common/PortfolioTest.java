package com.ciaozn.alphatrader.common;

import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void opensPositionAndTracksEquityWithMarkPrice() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.mark(BTC, d("100"));

        portfolio.applyFill(BTC, Side.BUY, d("100"), d("1"), d("0.05"));

        Position position = portfolio.position(BTC);
        assertThat(position.direction()).isEqualTo(Direction.LONG);
        assertThat(position.qty()).isEqualByComparingTo(d("1"));
        assertThat(position.entryPrice()).isEqualByComparingTo(d("100"));
        // only the fee left the account so far
        assertThat(portfolio.cash()).isEqualByComparingTo(d("9999.95"));
        assertThat(portfolio.equity()).isEqualByComparingTo(d("9999.95"));

        portfolio.mark(BTC, d("110"));
        assertThat(portfolio.unrealizedPnl()).isEqualByComparingTo(d("10"));
        assertThat(portfolio.equity()).isEqualByComparingTo(d("10009.95"));
    }

    @Test
    void addingToPositionReWeightsAverageEntryPrice() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.BUY, d("100"), d("1"), BigDecimal.ZERO);
        portfolio.applyFill(BTC, Side.BUY, d("120"), d("1"), BigDecimal.ZERO);

        Position position = portfolio.position(BTC);
        assertThat(position.qty()).isEqualByComparingTo(d("2"));
        assertThat(position.entryPrice()).isEqualByComparingTo(d("110"));
        assertThat(portfolio.realizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void reducingRealizesPnlOnClosedPartOnly() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.BUY, d("100"), d("2"), BigDecimal.ZERO);

        Portfolio.FillResult result = portfolio.applyFill(BTC, Side.SELL, d("130"), d("1"), d("0.065"));

        assertThat(result.realizedPnl()).isEqualByComparingTo(d("30"));
        assertThat(portfolio.position(BTC).qty()).isEqualByComparingTo(d("1"));
        // entry price is untouched by a reduction
        assertThat(portfolio.position(BTC).entryPrice()).isEqualByComparingTo(d("100"));
        assertThat(portfolio.cash()).isEqualByComparingTo(d("10029.935"));
    }

    @Test
    void shortPositionProfitsWhenPriceFalls() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.SELL, d("100"), d("2"), BigDecimal.ZERO);

        Portfolio.FillResult result = portfolio.applyFill(BTC, Side.BUY, d("90"), d("1"), BigDecimal.ZERO);

        assertThat(result.realizedPnl()).isEqualByComparingTo(d("10"));
        assertThat(portfolio.position(BTC).direction()).isEqualTo(Direction.SHORT);
        assertThat(portfolio.position(BTC).qty()).isEqualByComparingTo(d("1"));

        portfolio.mark(BTC, d("80"));
        assertThat(portfolio.unrealizedPnl()).isEqualByComparingTo(d("20"));
    }

    @Test
    void flippingRealizesWholeOldPositionAndReopensAtFillPrice() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.BUY, d("100"), d("1"), BigDecimal.ZERO);

        Portfolio.FillResult result = portfolio.applyFill(BTC, Side.SELL, d("110"), d("3"), BigDecimal.ZERO);

        assertThat(result.realizedPnl()).isEqualByComparingTo(d("10"));
        Position position = portfolio.position(BTC);
        assertThat(position.direction()).isEqualTo(Direction.SHORT);
        assertThat(position.qty()).isEqualByComparingTo(d("2"));
        assertThat(position.entryPrice()).isEqualByComparingTo(d("110"));
    }

    @Test
    void fundingIsPaidByLongsAndReceivedByShorts() {
        Portfolio longSide = new Portfolio(d("10000"));
        longSide.applyFill(BTC, Side.BUY, d("100"), d("1"), BigDecimal.ZERO);
        longSide.mark(BTC, d("20000"));
        // notional 20000 * 0.01% = 2 USDT cost
        assertThat(longSide.applyFunding(BTC, d("0.0001"))).isEqualByComparingTo(d("2"));
        assertThat(longSide.cash()).isEqualByComparingTo(d("9998"));
        assertThat(longSide.fundingTotal()).isEqualByComparingTo(d("2"));

        Portfolio shortSide = new Portfolio(d("10000"));
        shortSide.applyFill(BTC, Side.SELL, d("100"), d("1"), BigDecimal.ZERO);
        shortSide.mark(BTC, d("20000"));
        assertThat(shortSide.applyFunding(BTC, d("0.0001"))).isEqualByComparingTo(d("-2"));
        assertThat(shortSide.cash()).isEqualByComparingTo(d("10002"));
    }

    @Test
    void flatPositionPaysNoFunding() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.mark(BTC, d("20000"));
        assertThat(portfolio.applyFunding(BTC, d("0.0001"))).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void aggregatesFeesAndKeepsSymbolsSeparate() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.BUY, d("100"), d("1"), d("0.05"));
        portfolio.applyFill(ETH, Side.BUY, d("50"), d("10"), d("0.025"));

        assertThat(portfolio.feeTotal()).isEqualByComparingTo(d("0.075"));
        assertThat(portfolio.openPositions()).hasSize(2);
        assertThat(portfolio.openPositions().get(0).symbol()).isEqualTo(BTC);
        assertThat(portfolio.openPositions().get(1).symbol()).isEqualTo(ETH);
        assertThat(portfolio.positionsBySymbol()).containsOnlyKeys(BTC, ETH);

        portfolio.mark(BTC, d("100"));
        portfolio.mark(ETH, d("50"));
        assertThat(portfolio.totalNotional()).isEqualByComparingTo(d("600"));
    }

    @Test
    void closedPositionDisappearsFromOpenPositions() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.BUY, d("100"), d("1"), BigDecimal.ZERO);
        portfolio.applyFill(BTC, Side.SELL, d("100"), d("1"), BigDecimal.ZERO);

        assertThat(portfolio.openPositions()).isEmpty();
        assertThat(portfolio.position(BTC).isEmpty()).isTrue();
        assertThat(portfolio.equity()).isEqualByComparingTo(d("10000"));
    }

    @Test
    void equityFallsBackToEntryPriceBeforeAnyMark() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.BUY, d("100"), d("1"), BigDecimal.ZERO);
        // no mark yet -> unrealized is 0, not a crash
        assertThat(portfolio.equity()).isEqualByComparingTo(d("10000"));
    }

    @Test
    void rejectsNonPositiveFillQuantity() {
        Portfolio portfolio = new Portfolio(d("10000"));
        assertThatThrownBy(() -> portfolio.applyFill(BTC, Side.BUY, d("100"), BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> portfolio.applyFill(BTC, Side.BUY, d("100"), d("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identicalFillSequencesProduceScaleIdenticalValues() {
        Portfolio a = replayThreeWayAverageEntry();
        Portfolio b = replayThreeWayAverageEntry();

        // (100*1 + 101*2) / 3 is a repeating decimal: it must terminate identically
        assertThat(a.position(BTC).entryPrice()).isEqualTo(b.position(BTC).entryPrice());
        assertThat(a.cash()).isEqualTo(b.cash());
        assertThat(a.equity()).isEqualTo(b.equity());
        assertThat(a.cash().scale()).isEqualTo(8);
        assertThat(a.equity().scale()).isEqualTo(8);
    }

    private Portfolio replayThreeWayAverageEntry() {
        Portfolio portfolio = new Portfolio(d("10000"));
        portfolio.applyFill(BTC, Side.BUY, d("100"), d("1"), d("0.0001"));
        portfolio.applyFill(BTC, Side.BUY, d("101"), d("2"), BigDecimal.ZERO);
        portfolio.mark(BTC, d("102"));
        return portfolio;
    }
}
