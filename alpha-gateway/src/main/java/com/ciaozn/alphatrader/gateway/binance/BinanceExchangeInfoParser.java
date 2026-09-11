package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure functions: Binance USDⓈ-M {@code /fapi/v1/exchangeInfo} -> the precision constraints this
 * system actually uses (FR-GW-03). The payload carries rate limits, margin tiers, settlement dates
 * and delivery months that nothing here reads; this class is the one place that knows which fields
 * matter and what their absence means. No network, no state - fully unit-testable.
 *
 * <p>Four choices here are load-bearing, and each would be wrong made the other way:
 * <ul>
 *   <li><b>stepSize comes from MARKET_LOT_SIZE, with LOT_SIZE only as a fallback.</b> The risk gate
 *       publishes MARKET orders and nothing else, so MARKET_LOT_SIZE is the filter the exchange
 *       judges the quantity against. Where the two disagree, a quantity floored to LOT_SIZE can be
 *       one MARKET_LOT_SIZE rejects - a dirty order, which is precisely what FR-GW-03 exists to
 *       prevent.</li>
 *   <li><b>A symbol with no MIN_NOTIONAL filter has no minimum.</b> Zero is the honest answer; a
 *       plausible-looking default would silently suppress legal small orders and the operator would
 *       never learn why. A filter that is <em>present</em> but carries no number fails loudly
 *       instead - that is a payload this code does not understand, and guessing there is how a wrong
 *       minimum reaches a real order.</li>
 *   <li><b>A wanted symbol that is missing, or present but not TRADING, is an error rather than an
 *       omission.</b> Omitting it would boot a system in which every signal for that symbol becomes
 *       a CRITICAL interception forever, discovered by watching alerts instead of by a startup that
 *       refuses to run. Failing here is the same discipline the credential validator uses.</li>
 *   <li><b>Every case where precision cannot be determined throws.</b> Producing rules with a
 *       guessed or zero tickSize/stepSize would look like success to the gate and let it size an
 *       order it cannot align - the one way to bypass the "rules missing = CRITICAL hard stop"
 *       guarantee (T209) without touching the gate.</li>
 * </ul>
 *
 * <p>The result is ordered by {@code wanted}, not by the payload, so a provider built from it is
 * deterministic across runs (NFR-04). The caller's own {@link Symbol} instances are reused rather
 * than re-parsed from the exchange's spelling.
 */
public final class BinanceExchangeInfoParser {

    /** The only status a symbol may have for this system to place orders against it. */
    private static final String STATUS_TRADING = "TRADING";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SNIPPET_CHARS = 200;

    private BinanceExchangeInfoParser() {
    }

    /**
     * @param wanted symbols to extract, in the order the result should come back. Every one of them
     *               must be present in the payload and trading - see the class javadoc for why a
     *               missing one is an error.
     * @return one {@link TradingRules} per wanted symbol, in {@code wanted}'s iteration order
     */
    public static List<TradingRules> parse(String payload, Collection<Symbol> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol is required to build trading rules");
        }
        JsonNode symbols = readTree(payload).path("symbols");
        if (!symbols.isArray() || symbols.isEmpty()) {
            throw new IllegalArgumentException("Not an exchangeInfo payload (no symbols array): "
                    + snippet(payload));
        }

        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode entry : symbols) {
            byName.put(entry.path("symbol").asText(), entry);
        }

        List<TradingRules> rules = new ArrayList<>(wanted.size());
        for (Symbol symbol : wanted) {
            JsonNode entry = byName.get(symbol.binance());
            if (entry == null) {
                throw new IllegalArgumentException("exchangeInfo has no entry for " + symbol.unified()
                        + " (looked up \"" + symbol.binance() + "\")");
            }
            requireTrading(symbol, entry);
            rules.add(rulesFor(symbol, entry));
        }
        return Collections.unmodifiableList(rules);
    }

    /**
     * Contract type is deliberately not checked: a delivery future carries a dated symbol
     * ({@code BTCUSDT_240329}) that could never equal a unified symbol's Binance spelling, so the
     * lookup above already excludes it.
     */
    private static void requireTrading(Symbol symbol, JsonNode entry) {
        String status = entry.path("status").asText("");
        if (!STATUS_TRADING.equals(status)) {
            throw new IllegalArgumentException(symbol.unified() + " cannot be traded: exchangeInfo status is "
                    + (status.isEmpty() ? "absent" : "\"" + status + "\"") + ", expected " + STATUS_TRADING);
        }
    }

    private static TradingRules rulesFor(Symbol symbol, JsonNode entry) {
        JsonNode filters = entry.path("filters");
        BigDecimal tickSize = decimal(symbol, requireFilter(symbol, filters, "PRICE_FILTER"), "tickSize");
        BigDecimal stepSize = decimal(symbol, lotSize(symbol, filters), "stepSize");
        return new TradingRules(symbol, tickSize, stepSize, minNotional(symbol, filters));
    }

    /** MARKET_LOT_SIZE first - see the class javadoc; LOT_SIZE for symbols that carry no market filter. */
    private static JsonNode lotSize(Symbol symbol, JsonNode filters) {
        JsonNode marketLotSize = filter(filters, "MARKET_LOT_SIZE");
        return marketLotSize != null ? marketLotSize : requireFilter(symbol, filters, "LOT_SIZE");
    }

    private static BigDecimal minNotional(Symbol symbol, JsonNode filters) {
        JsonNode filter = filter(filters, "MIN_NOTIONAL");
        if (filter == null) {
            return BigDecimal.ZERO;
        }
        // Futures spell the field "notional"; spot and older futures revisions have used "minNotional".
        return decimal(symbol, filter, "notional", "minNotional");
    }

    private static JsonNode requireFilter(Symbol symbol, JsonNode filters, String filterType) {
        JsonNode filter = filter(filters, filterType);
        if (filter == null) {
            throw new IllegalArgumentException("exchangeInfo for " + symbol.unified() + " has no "
                    + filterType + " filter, so its precision is unknown");
        }
        return filter;
    }

    private static JsonNode filter(JsonNode filters, String filterType) {
        for (JsonNode entry : filters) {
            if (filterType.equals(entry.path("filterType").asText())) {
                return entry;
            }
        }
        return null;
    }

    /** The first of {@code fields} that is present, non-null and non-blank, parsed as a decimal. */
    private static BigDecimal decimal(Symbol symbol, JsonNode filter, String... fields) {
        for (String field : fields) {
            JsonNode value = filter.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return decimal(symbol, field, value.asText());
            }
        }
        throw new IllegalArgumentException("exchangeInfo " + filter.path("filterType").asText() + " for "
                + symbol.unified() + " carries none of " + String.join("/", fields) + ": "
                + snippet(filter.toString()));
    }

    private static BigDecimal decimal(Symbol symbol, String field, String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("exchangeInfo " + field + " for " + symbol.unified()
                    + " is not a number: \"" + text + "\"", e);
        }
    }

    private static JsonNode readTree(String payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (IOException e) {
            // The payload is ~500KB; it stays out of the message and the cause carries the position.
            throw new UncheckedIOException("Malformed exchangeInfo payload", e);
        }
    }

    private static String snippet(String text) {
        return text.length() <= MAX_SNIPPET_CHARS ? text : text.substring(0, MAX_SNIPPET_CHARS) + "...";
    }
}
