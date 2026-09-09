package com.ciaozn.alphatrader.backtest.match;

import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor.CostModel;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor.FundingSettlement;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor.SimulatedFill;
import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.KlineEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Kline;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedExecutorTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long HOUR = 3_600_000L;
    /** 2023-11-15T00:00:00Z - an exact UTC funding boundary, so boundary maths is not guesswork. */
    private static final long BASE = 28_800_000L * 59_028L;
    private static final TradingRules BTC_RULES =
            new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("100"));
    private static final TradingRules ETH_RULES =
            new TradingRules(ETH, new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("20"));

    private final RecordingPublisher publisher = new RecordingPublisher();
    private Portfolio portfolio;
    private SimulatedExecutor executor;

    @BeforeEach
    void setUp() {
        start(CostModel.DEFAULT, FixedTradingRulesProvider.of(BTC_RULES, ETH_RULES));
    }

    private void start(CostModel cost, TradingRulesProvider provider) {
        portfolio = new Portfolio(new BigDecimal("100000"));
        executor = new SimulatedExecutor(portfolio, provider, cost);
        publisher.published.clear();
        publisher.onPublish = event -> {
        };
    }

    // ------------------------------------------------------------------ fixtures

    private static Kline bar(long openTime, String open, String high, String low, String close) {
        return new Kline(openTime, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), new BigDecimal("1000"), openTime + HOUR - 1);
    }

    private static KlineEvent closed(Kline kline, Symbol symbol) {
        return KlineEvent.of(symbol, Interval.H1, kline, true, kline.closeTime());
    }

    private static KlineEvent forming(Kline kline, Symbol symbol) {
        return KlineEvent.of(symbol, Interval.H1, kline, false, kline.openTime());
    }

    /** The bar a signal is produced by: 2% amplitude, so slippage is 5bp fixed + 10bp amplitude. */
    private static Kline placingBar() {
        return bar(BASE, "200.00", "202.00", "198.00", "201.00");
    }

    /** The bar it fills on, with an absurd range: none of high/low/close may reach the price. */
    private static Kline fillingBar() {
        return bar(BASE + HOUR, "210.00", "999.00", "0.50", "500.00");
    }

    private void feed(Kline kline, Symbol symbol) {
        executor.onEvent(closed(kline, symbol), publisher);
    }

    private void place(String clientOrderId, Symbol symbol, Side side, String qty, long businessTs) {
        executor.onEvent(OrderRequestEvent.of(clientOrderId, symbol, side, OrderType.MARKET,
                new BigDecimal(qty), null, businessTs), publisher);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(8);
    }

    private List<RiskAlertEvent> alerts() {
        return publisher.published.stream()
                .filter(RiskAlertEvent.class::isInstance)
                .map(RiskAlertEvent.class::cast)
                .toList();
    }

    private List<FillEvent> fillEvents() {
        return publisher.published.stream()
                .filter(FillEvent.class::isInstance)
                .map(FillEvent.class::cast)
                .toList();
    }

    // ------------------------------------------------------------------ anti-look-ahead

    @Test
    void anOrderFillsOnlyWhenTheNextBarArrivesNeverOnTheBarThatProducedIt() {
        Kline signal = placingBar();
        feed(signal, BTC);
        place("c-1", BTC, Side.BUY, "1.000", signal.closeTime());

        assertThat(executor.fills()).as("the signal bar must not fill its own order").isEmpty();
        assertThat(executor.pendingOrders()).hasSize(1);

        feed(fillingBar(), BTC);

        assertThat(executor.fills()).hasSize(1);
        assertThat(executor.pendingOrders()).isEmpty();
    }

    @Test
    void theFillPriceIsTheNextBarsOpenAloneNotItsHighLowOrClose() {
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        SimulatedFill fill = executor.fills().get(0);
        // 15bp of slippage on the 210.00 open = 210.315, then tick-aligned against the trader
        assertThat(fill.fillPrice()).isEqualTo(new BigDecimal("210.40"));
        assertThat(fill.barOpen()).isEqualByComparingTo("210.00");
        assertThat(fill.fillBarOpenTime()).isEqualTo(BASE + HOUR);
        // the filling bar closed at 500 and traded 0.50..999; had any of that leaked into the
        // price, the fill would be a function of the future and the backtest would be inflated
        assertThat(fill.fillPrice()).isLessThan(new BigDecimal("211"));
        assertThat(fillEvents().get(0).price()).isEqualByComparingTo("210.4");
    }

    @Test
    void slippageIsEstimatedFromTheBarTheOrderWasPlacedIntoNotTheOneItFillsOn() {
        // same filling bar in both runs, so any difference can only come from the placing bar
        start(CostModel.DEFAULT, FixedTradingRulesProvider.of(BTC_RULES));
        feed(bar(BASE, "200.00", "200.00", "200.00", "200.00"), BTC);
        place("flat", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);
        BigDecimal flatSlip = executor.fills().get(0).fillPrice();

        start(CostModel.DEFAULT, FixedTradingRulesProvider.of(BTC_RULES));
        feed(placingBar(), BTC);
        place("volatile", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);
        SimulatedFill volatileFill = executor.fills().get(0);

        // 5bp fixed on a flat bar, 15bp on a 2% bar: the fill bar's own 0.50..999 range is
        // future information at its open and must not be what prices the trade
        assertThat(flatSlip).isEqualTo(new BigDecimal("210.20"));
        assertThat(volatileFill.slippageBps()).isEqualByComparingTo("15");
        assertThat(volatileFill.fillPrice()).isEqualTo(new BigDecimal("210.40"));
    }

    @Test
    void aFormingBarNeverTrades() {
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);

        executor.onEvent(forming(fillingBar(), BTC), publisher);

        assertThat(executor.fills()).as("a forming bar's open is not a tradable print").isEmpty();
        assertThat(executor.pendingOrders()).hasSize(1);

        feed(fillingBar(), BTC);
        assertThat(executor.fills()).hasSize(1);
    }

    @Test
    void anOrderWaitsForABarOfItsOwnSymbol() {
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);

        feed(bar(BASE + HOUR, "3000.00", "3010.00", "2990.00", "3005.00"), ETH);

        assertThat(executor.fills()).as("an ETH bar cannot fill a BTC order").isEmpty();

        feed(fillingBar(), BTC);
        assertThat(executor.fills()).hasSize(1);
    }

    // ------------------------------------------------------------------ cost model

    @Test
    void buysFillAboveTheOpenAndSellsBelowIt() {
        feed(placingBar(), BTC);
        place("buy", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);
        BigDecimal buyPrice = executor.fills().get(0).fillPrice();

        // same 15bp slip, same 210.00 open: only the rounding direction differs
        start(CostModel.DEFAULT, FixedTradingRulesProvider.of(BTC_RULES));
        feed(placingBar(), BTC);
        place("sell", BTC, Side.SELL, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);
        BigDecimal sellPrice = executor.fills().get(0).fillPrice();

        // both round against the trader: that is the point of a simulated exchange
        assertThat(buyPrice).isEqualTo(new BigDecimal("210.40"));
        assertThat(sellPrice).isEqualTo(new BigDecimal("209.60"));
        assertThat(buyPrice).isGreaterThan(new BigDecimal("210.00"));
        assertThat(sellPrice).isLessThan(new BigDecimal("210.00"));
    }

    @Test
    void theFeeIsChargedOnTheSlippedPriceAndLandsInTheBook() {
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        SimulatedFill fill = executor.fills().get(0);
        // 210.4 * 1.000 * 0.0005 taker
        assertThat(fill.fee()).isEqualTo(money("0.1052"));
        assertThat(fillEvents().get(0).fee()).isEqualTo(money("0.1052"));
        assertThat(portfolio.feeTotal()).isEqualTo(money("0.1052"));
        assertThat(portfolio.cash()).isEqualTo(money("99999.8948"));
        // the quantity is passed through at the scale the risk gate aligned it to - the exchange
        // does not re-round it (the book itself normalizes to the canonical money scale)
        assertThat(fill.qty()).isEqualTo(new BigDecimal("1.000"));
        assertThat(fillEvents().get(0).qty()).isEqualTo(new BigDecimal("1.000"));
        assertThat(portfolio.position(BTC).qty()).isEqualByComparingTo("1");
        assertThat(portfolio.position(BTC).entryPrice()).isEqualTo(new BigDecimal("210.40"));
        assertThat(fill.realizedPnl()).isEqualByComparingTo("0");
    }

    @Test
    void closingAPositionRealizesPnlAgainstTheFillPrice() {
        feed(placingBar(), BTC);
        place("open", BTC, Side.BUY, "2.000", BASE + HOUR - 1);
        feed(bar(BASE + HOUR, "210.00", "212.00", "208.00", "211.00"), BTC);
        // placed into a 4/210 amplitude bar: 5bp fixed + 9.52bp amplitude = 14.52bp
        place("close", BTC, Side.SELL, "2.000", BASE + 2 * HOUR - 1);
        feed(bar(BASE + 2 * HOUR, "220.00", "221.00", "219.00", "220.50"), BTC);

        SimulatedFill opening = executor.fills().get(0);
        SimulatedFill closing = executor.fills().get(1);
        assertThat(opening.fillPrice()).isEqualTo(new BigDecimal("210.40"));
        // 220 * (1 - 0.00145238) = 219.6805 -> 219.6, floored against the trader
        assertThat(closing.fillPrice()).isEqualTo(new BigDecimal("219.60"));
        assertThat(closing.realizedPnl()).isEqualByComparingTo("18.4");
        assertThat(portfolio.realizedPnl()).isEqualByComparingTo("18.4");
        assertThat(portfolio.position(BTC).isEmpty()).isTrue();
    }

    @Test
    void ordersForOneSymbolFillInPlacementOrder() {
        feed(placingBar(), BTC);
        place("first", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        place("second", BTC, Side.BUY, "2.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        // FIFO at one price: a single open gives the book nothing to walk
        assertThat(executor.fills()).extracting(SimulatedFill::clientOrderId)
                .containsExactly("first", "second");
        assertThat(executor.fills()).extracting(SimulatedFill::fillPrice)
                .containsOnly(new BigDecimal("210.40"));
        assertThat(portfolio.position(BTC).qty()).isEqualByComparingTo("3.000");
    }

    // ------------------------------------------------------------------ funding

    @Test
    void fundingSettlesOnceOnTheBoundaryAfterThePositionOpened() {
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);
        assertThat(executor.funding()).as("no boundary crossed yet").isEmpty();

        feed(bar(BASE + 8 * HOUR, "220.00", "225.00", "215.00", "222.00"), BTC);

        assertThat(executor.funding()).hasSize(1);
        FundingSettlement settlement = executor.funding().get(0);
        assertThat(settlement.boundaryMillis()).isEqualTo(BASE + 8 * HOUR);
        assertThat(settlement.markPrice()).isEqualByComparingTo("222.00");
        // a long pays a positive rate: 1.000 * 222 * 0.0001
        assertThat(settlement.charge()).isEqualByComparingTo("0.0222");
        assertThat(portfolio.fundingTotal()).isEqualTo(money("0.0222"));
        assertThat(portfolio.cash()).isEqualTo(money("99999.8726"));
    }

    @Test
    void aShortReceivesFundingWhenTheRateIsPositive() {
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.SELL, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);
        feed(bar(BASE + 8 * HOUR, "220.00", "225.00", "215.00", "222.00"), BTC);

        assertThat(executor.funding()).hasSize(1);
        assertThat(executor.funding().get(0).charge()).isEqualByComparingTo("-0.0222");
        // income, so cash went up by it: 100000 - fee(0.1048) + 0.0222
        assertThat(portfolio.cash()).isEqualTo(money("99999.9174"));
    }

    @Test
    void aFlatBookSettlesNoFunding() {
        for (int index = 0; index < 30; index++) {
            feed(bar(BASE + index * HOUR, "200", "201", "199", "200"), BTC);
        }
        assertThat(executor.funding()).isEmpty();
        assertThat(portfolio.fundingTotal()).isEqualByComparingTo("0");
    }

    @Test
    void aDataHoleStillSettlesEveryBoundaryTheExchangeReallySettled() {
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        // 24 hours of missing bars: the exchange settled three times regardless
        feed(bar(BASE + 24 * HOUR, "240.00", "241.00", "239.00", "240.00"), BTC);

        assertThat(executor.funding()).extracting(FundingSettlement::boundaryMillis)
                .containsExactly(BASE + 8 * HOUR, BASE + 16 * HOUR, BASE + 24 * HOUR);
        assertThat(portfolio.fundingTotal()).isEqualByComparingTo("0.072");
    }

    @Test
    void fundingNeverSettlesTheBoundariesBeforeTheRunStarted() {
        // a position that predates the data (a resumed run, a fixture): without the anchor the
        // first bar would settle every boundary since the epoch
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("200"), new BigDecimal("1.000"), money("0"));
        portfolio.mark(BTC, new BigDecimal("200"));

        feed(bar(BASE, "200", "201", "199", "200"), BTC);

        assertThat(executor.funding()).as("boundaries before the first bar are not ours to settle")
                .isEmpty();

        feed(bar(BASE + 8 * HOUR, "200", "201", "199", "200"), BTC);

        assertThat(executor.funding()).extracting(FundingSettlement::boundaryMillis)
                .containsExactly(BASE + 8 * HOUR);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void anOrderWhoseSlippedNotionalFallsUnderTheMinimumIsRefusedNotSentDirty() {
        feed(placingBar(), BTC);
        place("tiny", BTC, Side.BUY, "0.010", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        // 0.010 * 210.4 = 2.104, under the 100 USDT minimum: skip and alert (spec edge case 4)
        assertThat(executor.fills()).isEmpty();
        assertThat(executor.ordersRejected()).isEqualTo(1);
        assertThat(executor.rejections().get(0).clientOrderId()).isEqualTo("tiny");
        assertThat(alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.ruleId()).isEqualTo(SimulatedExecutor.RULE_BELOW_MIN_NOTIONAL);
            assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        });
        assertThat(portfolio.position(BTC).isEmpty()).isTrue();
        assertThat(portfolio.cash()).isEqualTo(money("100000"));
    }

    @Test
    void aLimitOrderIsRefusedBecauseThereIsNoBookToMatchItAgainst() {
        feed(placingBar(), BTC);
        executor.onEvent(OrderRequestEvent.of("limit-1", BTC, Side.BUY, OrderType.LIMIT,
                new BigDecimal("1.000"), new BigDecimal("190.00"), BASE + HOUR - 1), publisher);
        feed(fillingBar(), BTC);

        assertThat(executor.fills()).isEmpty();
        assertThat(executor.pendingOrders()).isEmpty();
        assertThat(alerts()).singleElement().satisfies(alert ->
                assertThat(alert.ruleId()).isEqualTo(SimulatedExecutor.RULE_UNSUPPORTED_ORDER_TYPE));
    }

    @Test
    void aNonPositiveQtyIsRefused() {
        feed(placingBar(), BTC);
        place("zero", BTC, Side.BUY, "0", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        assertThat(executor.fills()).isEmpty();
        assertThat(alerts()).singleElement().satisfies(alert ->
                assertThat(alert.ruleId()).isEqualTo(SimulatedExecutor.RULE_INVALID_QTY));
    }

    @Test
    void missingTradingRulesAtFillTimeIsACriticalStopNotAGuessedTickSize() {
        start(CostModel.DEFAULT, FixedTradingRulesProvider.of(ETH_RULES));
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        assertThat(executor.fills()).isEmpty();
        assertThat(alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.ruleId()).isEqualTo(SimulatedExecutor.RULE_NO_TRADING_RULES);
            assertThat(alert.severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        });
    }

    @Test
    void costModelRefusesTypoSizedValues() {
        assertThatThrownBy(() -> new CostModel(new BigDecimal("0.05"), new BigDecimal("5"),
                new BigDecimal("0.05"), new BigDecimal("0.0001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takerFeeRate");
        assertThatThrownBy(() -> new CostModel(new BigDecimal("0.0005"), new BigDecimal("5000"),
                new BigDecimal("0.05"), new BigDecimal("0.0001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixedSlippageBps");
        assertThatThrownBy(() -> new CostModel(new BigDecimal("0.0005"), new BigDecimal("5"),
                new BigDecimal("-1"), new BigDecimal("0.0001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amplitudeFactor");
        assertThatThrownBy(() -> new CostModel(new BigDecimal("0.0005"), new BigDecimal("5"),
                new BigDecimal("0.05"), new BigDecimal("0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fundingRatePerInterval");
    }

    // ------------------------------------------------------------------ book ordering

    @Test
    void theBookIsUpdatedBeforeAnythingReactingToTheFillSeesIt() {
        List<BigDecimal> observedAtFill = new ArrayList<>();
        publisher.onPublish = event -> {
            if (event instanceof FillEvent) {
                observedAtFill.add(portfolio.position(BTC).signedQty());
            }
        };
        feed(placingBar(), BTC);
        place("c-1", BTC, Side.BUY, "1.000", BASE + HOUR - 1);
        feed(fillingBar(), BTC);

        // a strategy's onFill must see the position the fill produced, not the one before it
        assertThat(observedAtFill).hasSize(1);
        assertThat(observedAtFill.get(0)).isEqualByComparingTo("1");
    }

    @Test
    void unfilledOrdersAreVisibleAtTheEndOfARun() {
        feed(placingBar(), BTC);
        place("never-filled", BTC, Side.BUY, "1.000", BASE + HOUR - 1);

        // the last signal of a replay has no next bar: report it rather than forget it
        assertThat(executor.pendingOrders()).extracting(OrderRequestEvent::clientOrderId)
                .containsExactly("never-filled");
        assertThat(executor.ordersReceived()).isEqualTo(1);
        assertThat(executor.ordersFilled()).isZero();
    }

    // ------------------------------------------------------------------ on the real loop

    @Test
    void aFillClosesInsideTheSameRoundAsTheBarThatTriggeredIt() throws InterruptedException {
        EventEngine engine = new EventEngine(EventJournal.noop(), new VirtualClock(BASE));
        Portfolio book = new Portfolio(new BigDecimal("100000"));
        SimulatedExecutor onLoop = new SimulatedExecutor(book,
                FixedTradingRulesProvider.of(BTC_RULES), CostModel.DEFAULT);
        List<Event> dispatched = new CopyOnWriteArrayList<>();
        engine.registerHandler(onLoop);
        // stands in for the strategy + risk chain: every closed bar produces one market order
        engine.registerHandler((event, pub) -> {
            dispatched.add(event);
            if (event instanceof KlineEvent kline && kline.closed()) {
                pub.publish(OrderRequestEvent.of("c-" + kline.kline().openTime(), BTC, Side.BUY,
                        OrderType.MARKET, new BigDecimal("1.000"), null, kline.timestamp()));
            }
        });
        engine.start();
        try {
            KlineEvent first = closed(placingBar(), BTC);
            engine.publish(first);
            assertThat(engine.awaitQuiescence(first.eventId(), Duration.ofSeconds(5))).isTrue();
            assertThat(onLoop.fills()).as("the signal bar must not fill its own order").isEmpty();

            KlineEvent second = closed(fillingBar(), BTC);
            engine.publish(second);
            assertThat(engine.awaitQuiescence(second.eventId(), Duration.ofSeconds(5))).isTrue();

            // the fill is a cascade event of the bar's round, so the replay barrier covers the
            // whole trading chain - which is what makes "one bar == one round" true end to end
            List<String> names = dispatched.stream().map(event -> event.getClass().getSimpleName()).toList();
            assertThat(names).containsSubsequence("KlineEvent", "OrderRequestEvent", "FillEvent");
            assertThat(onLoop.fills()).hasSize(1);
            assertThat(book.position(BTC).qty()).isEqualByComparingTo("1.000");
        } finally {
            engine.stop();
        }
    }

    /** Collects what the executor emitted, and can hand each event to an observer first. */
    private static final class RecordingPublisher implements EventPublisher {

        private final List<Event> published = new ArrayList<>();
        private Consumer<Event> onPublish = event -> {
        };

        @Override
        public void publish(Event event) {
            published.add(event);
            onPublish.accept(event);
        }
    }
}
