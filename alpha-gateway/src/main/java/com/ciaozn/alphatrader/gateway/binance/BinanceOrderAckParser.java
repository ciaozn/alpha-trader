package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.gateway.OrderAck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Binance order-placement response -> {@link OrderAck} (T316).
 *
 * <p>The two shapes are told apart by {@code code}, not by HTTP status: a refused order comes back
 * as HTTP 400 with {@code {"code":-2010,"msg":"..."}}. Both carry a body, so a parser that only
 * looked at the status line would read a refusal as a success and the OMS would wait forever for an
 * order that does not exist.
 */
public final class BinanceOrderAckParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BinanceOrderAckParser() {
    }

    public static OrderAck parse(String body, String clientOrderId) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode code = root.get("code");
            if (code != null && code.asLong() != 0L) {
                return OrderAck.rejected(clientOrderId, "binance error " + code.asLong() + ": "
                        + root.path("msg").asText("(no message)"));
            }
            JsonNode orderId = root.get("orderId");
            if (orderId == null) {
                throw new IllegalArgumentException("Order response carries no orderId: " + body);
            }
            return OrderAck.accepted(clientOrderId, orderId.asText());
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed order response", e);
        }
    }
}
