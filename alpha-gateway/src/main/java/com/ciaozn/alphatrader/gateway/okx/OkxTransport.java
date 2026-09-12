package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;

import java.util.Map;

/**
 * HTTP seam for OKX (T406). Unlike Binance, OKX takes its parameters as a JSON body on POST, so this
 * carries a body where {@code BinanceTransport} does not.
 *
 * <p>Same contract as the Binance seam on the one thing that matters: transport failure and 5xx/429
 * become {@link ExchangeUnreachableException} (we do not know what happened to the order), while a
 * business refusal comes back as a body for the caller to turn into a rejected ack. OKX reports
 * business errors inside a 200 response, so the body is where the answer is either way.
 */
public interface OkxTransport extends AutoCloseable {

    String call(String method, String url, Map<String, String> headers, String body);

    @Override
    void close();
}
