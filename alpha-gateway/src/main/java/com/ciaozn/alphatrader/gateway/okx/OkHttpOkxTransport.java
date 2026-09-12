package com.ciaozn.alphatrader.gateway.okx;

import com.ciaozn.alphatrader.gateway.ExchangeUnreachableException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Map;

/** OkHttp implementation of {@link OkxTransport}. No retries - see {@code OrderSender} for why. */
public final class OkHttpOkxTransport implements OkxTransport {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;

    public OkHttpOkxTransport(OkHttpClient http) {
        if (http == null) {
            throw new IllegalArgumentException("http client is required");
        }
        this.http = http;
    }

    @Override
    public String call(String method, String url, Map<String, String> headers, String body) {
        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::header);
        switch (method.toUpperCase()) {
            case "GET" -> builder.get();
            case "POST" -> builder.post(RequestBody.create(body == null ? "" : body, JSON));
            case "DELETE" -> builder.delete();
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            int code = response.code();
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (code >= 200 && code < 300) {
                return text;
            }
            if (code == 429 || code >= 500) {
                throw new ExchangeUnreachableException("OKX REST " + method + " " + url + " → HTTP " + code);
            }
            return text;
        } catch (IOException e) {
            throw new ExchangeUnreachableException("OKX REST " + method + " " + url + " failed", e);
        }
    }

    @Override
    public void close() {
        http.dispatcher().cancelAll();
    }
}
