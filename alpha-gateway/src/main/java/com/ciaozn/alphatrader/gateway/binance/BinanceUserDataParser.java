package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.common.event.OrderReportEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Binance user data stream frame -> {@link OrderReportEvent} (T317).
 *
 * <p><b>Two shapes come out of one frame type.</b> {@code ORDER_TRADE_UPDATE} reports both lifecycle
 * changes and executions, and the difference is {@code l} (last executed quantity): non-zero means
 * this frame carries a fill, so it becomes {@code OrderReportEvent.ofTrade(...)} and the OMS applies
 * it to the book; zero means only the status changed. Deciding this here, at the edge, is what keeps
 * "an execution happened" from being inferred twice - once from the status and once from the fill.
 *
 * <p><b>Trade reports carry no status.</b> PARTIALLY_FILLED vs FILLED is derived by the OMS from the
 * order's own quantities, because the exchange's status and our cumulative fill can disagree for one
 * frame and only the quantities are unambiguous.
 *
 * <p>Anything that is not an order report (ACCOUNT_UPDATE, listenKeyExpired, margin calls) returns
 * empty: this stream is the single entrance for exchange facts about orders (FR-EX-03), not a
 * general-purpose subscription we might later be tempted to route around the OMS.
 */
public final class BinanceUserDataParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORDER_TRADE_UPDATE = "ORDER_TRADE_UPDATE";

    private BinanceUserDataParser() {
    }

    public static Optional<OrderReportEvent> parse(String payload) {
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || !ORDER_TRADE_UPDATE.equals(data.path("e").asText())) {
                return Optional.empty();
            }
            JsonNode order = data.get("o");
            if (order == null) {
                return Optional.empty();
            }
            String clientOrderId = order.path("c").asText();
            String exchangeOrderId = order.path("i").asText(null);
            long tradeTime = order.path("T").asLong(data.path("E").asLong());
            BigDecimal lastQty = new BigDecimal(order.path("l").asText("0"));

            if (lastQty.signum() > 0) {
                BigDecimal lastPrice = new BigDecimal(order.path("L").asText("0"));
                BigDecimal fee = new BigDecimal(order.path("n").asText("0"));
                return Optional.of(OrderReportEvent.ofTrade(clientOrderId, exchangeOrderId,
                        lastQty, lastPrice, fee, tradeTime));
            }
            String status = order.path("X").asText("");
            return Optional.of(OrderReportEvent.of(clientOrderId, exchangeOrderId,
                    BinanceOrderStatus.to(status), status, tradeTime));
        } catch (IOException e) {
            throw new UncheckedIOException("Malformed user data frame", e);
        }
    }
}
