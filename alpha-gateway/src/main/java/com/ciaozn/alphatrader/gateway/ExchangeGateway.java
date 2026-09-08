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
 */
public interface ExchangeGateway extends AutoCloseable {

    void connect(GatewayConfig config);

    void subscribeKline(Symbol symbol, Interval interval);

    /** Synchronous order placement (P3). Returns the exchange ack; fills arrive via user data stream. */
    OrderAck placeOrder(OrderRequestEvent request);

    void cancelOrder(String clientOrderId);

    AccountSnapshot queryAccount();

    List<Position> queryPositions();

    /** All resting orders - used by periodic reconciliation (FR-EX-04). */
    List<OpenOrder> queryOpenOrders();

    @Override
    void close();
}
