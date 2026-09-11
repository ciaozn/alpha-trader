package com.ciaozn.alphatrader.gateway.binance;

import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * OkHttp implementation of {@link BinanceTransport} (T316).
 *
 * <p>No retries here. A retry belongs to the code that knows whether the request is idempotent, and
 * that is the caller: re-sending an order placement must reuse the same {@code clientOrderId}, which
 * lives in {@link com.ciaozn.alphatrader.common.event.OrderRequestEvent}, not in this class. The
 * order sender deliberately hands an unreachable exchange to reconciliation instead of retrying
 * blindly, because a retry of a request that actually succeeded is a duplicate position.
 */
public final class OkHttpBinanceTransport implements BinanceTransport {

    private static final Logger log = LoggerFactory.getLogger(OkHttpBinanceTransport.class);
    private static final RequestBody EMPTY_BODY = RequestBody.create(null, new byte[0]);

    private final OkHttpClient http;

    public OkHttpBinanceTransport(OkHttpClient http) {
        if (http == null) {
            throw new IllegalArgumentException("http client is required");
        }
        this.http = http;
    }

    @Override
    public String call(String method, String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::header);
        switch (method.toUpperCase()) {
            case "GET" -> builder.get();
            // All three write methods take their parameters in the URL, so the body is empty:
            // Binance futures signs the query string, and a body would be a second thing to sign.
            case "POST" -> builder.post(EMPTY_BODY);
            case "PUT" -> builder.put(EMPTY_BODY);
            case "DELETE" -> builder.delete();
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            int code = response.code();
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (code >= 200 && code < 300) {
                return text;
            }
            if (code == 429 || code == 418 || code >= 500) {
                // The exchange is telling us to come back, or is broken: either way we do not know
                // what happened to the order, which is exactly what unreachable means.
                throw new ExchangeUnreachableException("Binance REST " + method + " " + url
                        + " → HTTP " + code);
            }
            if (code >= 400 && code < 500) {
                // A refusal with reasons. Returned so the caller can read {code,msg} and record a
                // REJECTED order rather than an unknown one.
                log.warn("Binance REST {} {} → HTTP {}: {}", method, url, code, text);
                return text;
            }
            throw new ExchangeUnreachableException("Binance REST " + method + " " + url
                    + " → unexpected HTTP " + code);
        } catch (IOException e) {
            throw new ExchangeUnreachableException("Binance REST " + method + " " + url + " failed", e);
        }
    }

    @Override
    public void close() {
        // The shared client is owned by whoever created it; only the dispatcher is nudged so a
        // shutdown does not leave connections hanging for the default keep-alive.
        http.dispatcher().cancelAll();
    }
}
