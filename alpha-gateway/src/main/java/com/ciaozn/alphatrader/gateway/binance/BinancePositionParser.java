package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.gateway.Position;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /fapi/v2/positionRisk} -> the positions the exchange holds (T316).
 *
 * <p>The endpoint answers with one row per symbol, flat ones included, and a row is a position only
 * when {@code positionAmt} is non-zero. Filtering here rather than at the caller is what keeps
 * "the exchange has no position in BTC" and "the exchange did not answer about BTC"
 * distinguishable: an empty list means genuinely flat, and an unreachable exchange throws instead.
 *
 * <p>Quantity is stored unsigned with a {@link Direction}, matching the book's own shape, so a
 * short of 0.5 and a long of 0.5 never differ by a sign convention buried in a parser.
 */
public final class BinancePositionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BinancePositionParser() {
    }

    public static List<Position> parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<Position> positions = new ArrayList<>();
            for (JsonNode row : root) {
                BigDecimal amount = new BigDecimal(row.path("positionAmt").asText("0"));
                if (amount.signum() == 0) {
                    continue;
                }
                Symbol symbol = Symbol.parse(row.path("symbol").asText() + ".PERP");
                positions.add(new Position(symbol,
                        amount.signum() > 0 ? Direction.LONG : Direction.SHORT,
                        amount.abs(),
                        new BigDecimal(row.path("entryPrice").asText("0")),
                        new BigDecimal(row.path("unRealizedProfit").asText("0"))));
            }
            return List.copyOf(positions);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed positionRisk response", e);
        }
    }
}
