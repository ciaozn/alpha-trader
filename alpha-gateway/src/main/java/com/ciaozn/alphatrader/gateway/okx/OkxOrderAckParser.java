package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.gateway.OrderAck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * OKX order response -> {@link OrderAck} (T406).
 *
 * <p><b>Two result codes, and both must be zero.</b> OKX answers with a top-level {@code code}
 * ("0" means the request was understood) and a per-order {@code sCode} inside {@code data} ("0" means
 * the order was accepted). A request can be well-formed and the order refused - insufficient margin,
 * a price band violation - so reading only the top-level code would record a REJECTED order as live
 * and the OMS would wait for a fill that will never come.
 */
public final class OkxOrderAckParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OkxOrderAckParser() {
    }

    public static OrderAck parse(String body, String clientOrderId) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String code = root.path("code").asText("");
            if (!"0".equals(code)) {
                return OrderAck.rejected(clientOrderId, "okx error " + code + ": " + root.path("msg").asText(""));
            }
            JsonNode first = root.path("data").path(0);
            if (first.isMissingNode()) {
                return OrderAck.rejected(clientOrderId, "okx returned no order data: " + body);
            }
            String sCode = first.path("sCode").asText("0");
            if (!"0".equals(sCode)) {
                return OrderAck.rejected(clientOrderId, "okx sCode " + sCode + ": " + first.path("sMsg").asText(""));
            }
            String orderId = first.path("ordId").asText(null);
            if (orderId == null || orderId.isBlank()) {
                return OrderAck.rejected(clientOrderId, "okx accepted without an order id: " + body);
            }
            return OrderAck.accepted(clientOrderId, orderId);
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed OKX order response", e);
        }
    }
}
