package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.util.List;

/**
 * The single abstraction over exchanges (DESIGN §6). Implementations:
 * BinanceFuturesGateway (live/testnet), OkxSwapGateway (P4), SimulatedGateway (P2 backtest).
 *
 * <p>Market data flows IN via {@link com.ciaozn.alphatrader.engine.EventEngine#publish};
 * trading flows OUT synchronously. Components outside this module never see exchange-specific
 * formats - only unified {@link Symbol} and standardized events.
 *
 * <p><b>Every method here reports "the exchange did not answer" by throwing
 * {@link ExchangeUnreachableException}, never by returning an empty or default value.</b> An empty
 * list of open orders during an outage reads as "the exchange holds nothing", and reconciliation -
 * whose rule is that the exchange is right - would then correct local state towards a fiction.
 */
public interface ExchangeGateway extends AutoCloseable {

    void connect(GatewayConfig config);

    void subscribeKline(Symbol symbol, Interval interval);

    /**
     * Synchronous order placement. Fills arrive via the user data stream, not through this call.
     *
     * <p><b>The returned ack is the exchange's answer, and a rejection is an answer</b>: the exchange
     * took the request and refused it, so the order is definitively not on the book. When there is no
     * answer at all - connection failure, timeout, maintenance window, 5xx - implementations throw
     * {@link ExchangeUnreachableException} instead of returning a rejection (边界 6). Conflating the
     * two is how a resting order gets a second one beside it: "unknown" recorded as "refused" sends the
     * strategy back to re-signal a position that is already live.
     *
     * <p>Retrying is the implementation's business, and is only safe while it knows the request never
     * reached the wire. A retry must reuse {@code request.clientOrderId()} (FR-EX-02) so that the
     * exchange refuses the duplicate rather than filling twice.
     */
    OrderAck placeOrder(OrderRequestEvent request);

    void cancelOrder(String clientOrderId);

    AccountSnapshot queryAccount();

    List<Position> queryPositions();

    /** All resting orders - used by periodic reconciliation (FR-EX-04). */
    List<OpenOrder> queryOpenOrders();

    @Override
    void close();
}
