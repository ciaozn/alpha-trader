package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;

import java.util.Map;

/**
 * The HTTP seam between {@link BinanceRestClient} and the network (T316).
 *
 * <p>One method, because every Binance futures call the client makes is a query string: even POST
 * and DELETE take their parameters in the URL, so there is no body to abstract over and no second
 * shape to keep in step.
 *
 * <p><b>The contract that matters is what it throws.</b> "The exchange did not answer" is a single
 * exception type for the whole system ({@link ExchangeUnreachableException}), and it is thrown here
 * and nowhere else: transport failure, HTTP 429/418 and HTTP 5xx all mean the same thing to the
 * caller - we do not know what happened to the order. A business refusal (HTTP 4xx with a Binance
 * error body) is NOT unreachable: the exchange answered, and it said no. Returning that body lets
 * the caller turn it into {@code OrderAck.rejected}, which the OMS records as REJECTED, while an
 * unreachable exchange leaves the order's fate unknown and is left to reconciliation (spec
 * edge case 6). Conflating the two is how a system decides an order failed when it actually filled.
 */
public interface BinanceTransport extends AutoCloseable {

    /**
     * @param method  GET / POST / DELETE
     * @param url     full URL including the signed query string
     * @param headers extra headers (notably {@code X-MBX-APIKEY})
     * @return the response body
     * @throws ExchangeUnreachableException if the exchange gave no answer we can act on
     */
    String call(String method, String url, Map<String, String> headers);

    @Override
    void close();
}
