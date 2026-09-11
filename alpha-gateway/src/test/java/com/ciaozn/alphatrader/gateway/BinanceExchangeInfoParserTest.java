package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.gateway.binance.BinanceExchangeInfoParser;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payloads here are Binance's own wire shape - real filter names, real values, real ordering
 * (PRICE_FILTER really does come after LOT_SIZE) - because what is being tested is which of ~500KB
 * of exchange metadata this system reads, and a hand-simplified payload would hide the filters that
 * must be ignored and the ordering that must not matter.
 */
class BinanceExchangeInfoParserTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");

    /** Real BTCUSDT perpetual filters, tick 0.10 / step 0.001 / minNotional 100, plus six that
     *  nothing here reads - their presence is what proves the lookup is by filterType and not by
     *  position, and that MIN_PRICE's minPrice is not mistaken for MIN_NOTIONAL's minimum. */
    private static final String BTC_FILTERS = """
            {"filterType":"MIN_PRICE","minPrice":"0.01"},\
            {"filterType":"LOT_SIZE","maxQty":"1000","stepSize":"0.001","minQty":"0.001"},\
            {"filterType":"MARKET_LOT_SIZE","maxQty":"150","stepSize":"0.001","minQty":"0.001"},\
            {"filterType":"MAX_NUM_ORDERS","maxNumOrders":"200"},\
            {"filterType":"MAX_NUM_ALGO_ORDERS","maxNumAlgoOrders":"5"},\
            {"filterType":"MAX_POSITION","maxPositionAmount":"1000"},\
            {"filterType":"PERCENT_PRICE","multiplierUp":"1.1000","multiplierDown":"0.9000","multiplierDecimal":"4"},\
            {"filterType":"PRICE_FILTER","minPrice":"0.01","maxPrice":"10000000","tickSize":"0.10"},\
            {"filterType":"MIN_NOTIONAL","notional":"100"}""";

    /** baseAsset/quoteAsset and the margin fields are left out: nothing reads them, and writing a
     *  wrong one into a builder that also makes ETHUSDT entries would be worse than omitting it. */
    private static String entry(String binanceSymbol, String status, String filters) {
        return "{\"symbol\":\"" + binanceSymbol + "\",\"pair\":\"" + binanceSymbol
                + "\",\"contractType\":\"PERPETUAL\",\"deliveryDate\":4133404800000,"
                + "\"onboardDate\":1569398400000,\"status\":\"" + status
                + "\",\"pricePrecision\":2,\"quantityPrecision\":3,\"filters\":[" + filters + "]}";
    }

    private static String exchangeInfo(String... entries) {
        return "{\"timezone\":\"UTC\",\"serverTime\":1700000000000,\"futuresType\":\"U\","
                + "\"rateLimits\":[{\"rateLimitType\":\"REQUEST_WEIGHT\",\"interval\":\"MINUTE\","
                + "\"intervalNum\":1,\"limit\":2400}],\"exchangeFilters\":[],\"symbols\":["
                + String.join(",", entries) + "]}";
    }

    private static TradingRules parseOne(String payload, Symbol symbol) {
        List<TradingRules> rules = BinanceExchangeInfoParser.parse(payload, List.of(symbol));
        assertThat(rules).hasSize(1);
        return rules.getFirst();
    }

    @Test
    void readsTickStepAndMinNotionalOutOfARealFilterList() {
        TradingRules rules = parseOne(exchangeInfo(entry("BTCUSDT", "TRADING", BTC_FILTERS)), BTC);

        assertThat(rules.symbol()).isEqualTo(BTC);
        assertThat(rules.tickSize()).isEqualByComparingTo("0.10");
        assertThat(rules.stepSize()).isEqualByComparingTo("0.001");
        assertThat(rules.minNotional()).isEqualByComparingTo("100");
        // Scale is load-bearing, not decoration: TradingRules floors to stepSize.scale() and aligns
        // to tickSize.scale(), so a step parsed at the wrong scale submits the wrong number of
        // decimal places. The exchange's own spelling has to survive the round trip.
        assertThat(rules.tickSize().toPlainString()).isEqualTo("0.10");
        assertThat(rules.stepSize().toPlainString()).isEqualTo("0.001");
    }

    @Test
    void theStepSizeIsTheMarketOneBecauseTheGateOnlySendsMarketOrders() {
        String filters = """
                {"filterType":"LOT_SIZE","minQty":"0.001","maxQty":"1000","stepSize":"0.001"},\
                {"filterType":"MARKET_LOT_SIZE","minQty":"0.001","maxQty":"150","stepSize":"0.005"},\
                {"filterType":"PRICE_FILTER","tickSize":"0.10"},\
                {"filterType":"MIN_NOTIONAL","notional":"100"}""";

        TradingRules rules = parseOne(exchangeInfo(entry("BTCUSDT", "TRADING", filters)), BTC);

        // 0.005, not LOT_SIZE's 0.001: flooring to the wrong one can produce a quantity the filter
        // that actually judges a market order rejects, which is the dirty order FR-GW-03 forbids.
        assertThat(rules.stepSize()).isEqualByComparingTo("0.005");
        assertThat(rules.isValidQty(new BigDecimal("0.003"))).isFalse();
        assertThat(rules.isValidQty(new BigDecimal("0.005"))).isTrue();
    }

    @Test
    void fallsBackToLotSizeWhenThereIsNoMarketLotSizeFilter() {
        String filters = """
                {"filterType":"LOT_SIZE","minQty":"0.001","maxQty":"1000","stepSize":"0.002"},\
                {"filterType":"PRICE_FILTER","tickSize":"0.10"}""";

        assertThat(parseOne(exchangeInfo(entry("BTCUSDT", "TRADING", filters)), BTC).stepSize())
                .isEqualByComparingTo("0.002");
    }

    @Test
    void aSymbolWithNoMinNotionalFilterGenuinelyHasNoMinimum() {
        String filters = """
                {"filterType":"LOT_SIZE","minQty":"0.001","maxQty":"1000","stepSize":"0.001"},\
                {"filterType":"MARKET_LOT_SIZE","minQty":"0.001","maxQty":"150","stepSize":"0.001"},\
                {"filterType":"PRICE_FILTER","tickSize":"0.10"}""";

        TradingRules rules = parseOne(exchangeInfo(entry("BTCUSDT", "TRADING", filters)), BTC);

        assertThat(rules.minNotional()).isEqualByComparingTo("0");
        // And it behaves as no minimum rather than as a minimum nobody can see: the smallest legal
        // quantity at the smallest price is tradable. A guessed default would have refused it.
        assertThat(rules.tradableQty(new BigDecimal("0.001"), new BigDecimal("1"))).isPresent();
    }

    @Test
    void theAlternativeSpellingOfMinNotionalIsReadToo() {
        String filters = """
                {"filterType":"LOT_SIZE","stepSize":"0.001"},{"filterType":"PRICE_FILTER","tickSize":"0.10"},\
                {"filterType":"MIN_NOTIONAL","minNotional":"20"}""";

        assertThat(parseOne(exchangeInfo(entry("BTCUSDT", "TRADING", filters)), BTC).minNotional())
                .isEqualByComparingTo("20");
    }

    @Test
    void rulesComeBackInTheOrderAskedForAndCarryTheCallersOwnSymbols() {
        // The exchange lists BTC first; determinism (NFR-04) means the result follows the caller,
        // whose order is the configured one, not the payload's, which can change between runs.
        String payload = exchangeInfo(entry("BTCUSDT", "TRADING", BTC_FILTERS),
                entry("ETHUSDT", "TRADING", BTC_FILTERS.replace("0.001", "0.01").replace("0.10", "0.01")));

        List<TradingRules> rules = BinanceExchangeInfoParser.parse(payload, List.of(ETH, BTC));

        assertThat(rules).extracting(TradingRules::symbol).containsExactly(ETH, BTC);
        assertThat(rules.get(0).stepSize()).isEqualByComparingTo("0.01");
        assertThat(rules.get(1).stepSize()).isEqualByComparingTo("0.001");
        // The instances handed in, not re-parsed copies: equal either way for a record, so identity
        // is the only thing that pins it, and re-parsing would put the parser's Symbol.parse rules
        // (USDT/USDC/USD only) between the caller's symbols and its own rules.
        assertThat(rules.get(0).symbol()).isSameAs(ETH);
        assertThat(rules.get(1).symbol()).isSameAs(BTC);
    }

    @Test
    void theNumbersThatComeOffTheWireAreWhatRefuseADirtyOrder() {
        TradingRules rules = parseOne(exchangeInfo(entry("BTCUSDT", "TRADING", BTC_FILTERS)), BTC);

        // FR-GW-03's point: fetched rules must actually align and refuse, not merely be stored.
        assertThat(rules.alignPrice(new BigDecimal("78546.75"), RoundingMode.FLOOR).toPlainString())
                .isEqualTo("78546.70");
        assertThat(rules.tradableQty(new BigDecimal("0.1239"), new BigDecimal("78000"))).isPresent()
                .get().extracting(BigDecimal::toPlainString).isEqualTo("0.123");
        // 0.001 BTC @ 78000 = 78 USDT < 100 minimum -> refused, so no order goes out at all
        assertThat(rules.tradableQty(new BigDecimal("0.001"), new BigDecimal("78000"))).isEmpty();
        assertThat(rules.isValidQty(new BigDecimal("0.1234"))).isFalse();
        assertThat(rules.isValidQty(new BigDecimal("0.123"))).isTrue();
    }

    @Test
    void aWantedSymbolTheExchangeDoesNotListIsAnError() {
        String payload = exchangeInfo(entry("BTCUSDT", "TRADING", BTC_FILTERS));

        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(payload, List.of(ETH)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ETHUSDT.PERP")
                .hasMessageContaining("ETHUSDT");
    }

    @Test
    void aSymbolThatIsNotTradingIsAnError() {
        String payload = exchangeInfo(entry("BTCUSDT", "SETTLING", BTC_FILTERS));

        // Not an omission: a provider missing one symbol boots fine and then turns every signal for
        // it into a CRITICAL interception forever. Refusing to start says it once, at the start.
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(payload, List.of(BTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BTCUSDT.PERP")
                .hasMessageContaining("SETTLING");
    }

    @Test
    void anUndeterminablePrecisionIsAnErrorRatherThanAGuess() {
        String noPriceFilter = exchangeInfo(entry("BTCUSDT", "TRADING",
                "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.001\"}"));
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(noPriceFilter, List.of(BTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BTCUSDT.PERP")
                .hasMessageContaining("PRICE_FILTER");

        String noLotSizeAtAll = exchangeInfo(entry("BTCUSDT", "TRADING",
                "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.10\"}"));
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(noLotSizeAtAll, List.of(BTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOT_SIZE");

        // Present but numberless is different from absent: absent means no minimum, this means a
        // payload this code does not understand, and a guess here becomes a real order's minimum.
        String numberlessMinimum = exchangeInfo(entry("BTCUSDT", "TRADING",
                "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.001\"},"
                        + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.10\"},"
                        + "{\"filterType\":\"MIN_NOTIONAL\"}"));
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(numberlessMinimum, List.of(BTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIN_NOTIONAL")
                .hasMessageContaining("notional");

        String notANumber = exchangeInfo(entry("BTCUSDT", "TRADING",
                "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.001\"},"
                        + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"abc\"}"));
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(notANumber, List.of(BTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BTCUSDT.PERP")
                .hasMessageContaining("tickSize")
                .hasMessageContaining("abc");
    }

    @Test
    void aPayloadThatIsNotExchangeInfoIsRejectedWithoutBeingInlined() {
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(
                "{\"timezone\":\"UTC\",\"rateLimits\":[],\"symbols\":[]}", List.of(BTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no symbols array");

        // exchangeInfo is ~500KB. A sentinel past the snippet limit proves the payload stayed out
        // of the message: an exception carrying half a megabyte lands in a log line nobody reads.
        String huge = "not json {{{" + "x".repeat(4000) + "SENTINEL";
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(huge, List.of(BTC)))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageNotContaining("SENTINEL");

        String noSymbolsKey = "{\"timezone\":\"UTC\"}" + "";
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(noSymbolsKey, List.of(BTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no symbols array");
    }

    @Test
    void anEmptyWantedListIsRejected() {
        String payload = exchangeInfo(entry("BTCUSDT", "TRADING", BTC_FILTERS));

        // Empty in, empty provider out would boot a system where the gate CRITICAL-blocks every
        // signal - the failure T209's hard stop exists to make loud, made quiet instead.
        assertThatThrownBy(() -> BinanceExchangeInfoParser.parse(payload, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one symbol");
    }
}
