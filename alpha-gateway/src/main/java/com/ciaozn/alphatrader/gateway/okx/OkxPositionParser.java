package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.gateway.Position;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /api/v5/account/positions} -> positions (T406).
 *
 * <p>OKX answers in net mode with a signed {@code pos} per instrument, which maps onto this system's
 * signed book without translation - a short is a negative number, exactly as {@code Portfolio} stores
 * it. Rows with a zero position are dropped so "flat" and "no answer" stay distinguishable, the same
 * contract the Binance parser keeps.
 */
public final class OkxPositionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OkxPositionParser() {
    }

    public static List<Position> parse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            List<Position> positions = new ArrayList<>();
            for (JsonNode row : root.path("data")) {
                BigDecimal pos = new BigDecimal(row.path("pos").asText("0"));
                if (pos.signum() == 0) {
                    continue;
                }
                positions.add(new Position(
                        OkxSymbols.toUnified(row.path("instId").asText()),
                        pos.signum() > 0 ? Direction.LONG : Direction.SHORT,
                        pos.abs(),
                        new BigDecimal(row.path("avgPx").asText("0")),
                        new BigDecimal(row.path("upl").asText("0"))));
            }
            return List.copyOf(positions);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed OKX positions response", e);
        }
    }
}
