package com.ciaozn.alphatrader.gateway.okx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * OKX request signing (T406): {@code base64(HMAC-SHA256(secret, timestamp + method + path + body))}.
 *
 * <p>Three differences from Binance's scheme are easy to get wrong and are the reason this is its own
 * class rather than a flag on {@code BinanceSigner}: the output is base64 rather than hex, the
 * timestamp is an ISO-8601 string with milliseconds that is also sent as a header (so it must be the
 * same string in both places), and <b>the query string is part of the signed path</b> - OKX's own
 * example is {@code HmacSHA256(timestamp + 'GET' + '/api/v5/account/balance?ccy=BTC', SecretKey)}.
 * Signing the bare path produces error 50113, which reads like a bad key and is not one.
 *
 * <p>An earlier version of this class asserted the opposite (that the query must be excluded) and
 * had a test pinning it. The test passed, the belief was wrong, and only the documentation settled
 * it - which is the argument for signing requests against the published formula rather than against
 * one's recollection of it.
 *
 * <p>A pure function, so a test can re-derive the digest with the JDK and compare bytes. The
 * timestamp is passed in rather than read from a clock: signing must be a function of its inputs.
 */
public final class OkxSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private OkxSigner() {
    }

    /**
     * @param timestampIso ISO-8601 UTC with milliseconds, e.g. {@code 2026-09-12T02:30:00.000Z}
     * @param method       upper-case HTTP method
     * @param requestPath  path including the leading slash AND the query string, exactly as sent
     * @param body         request body, empty string for GET
     */
    public static String sign(String timestampIso, String method, String requestPath, String body, String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("API secret is required to sign a request");
        }
        if (timestampIso == null || method == null || requestPath == null) {
            throw new IllegalArgumentException("timestamp, method and requestPath are required");
        }
        String preimage = timestampIso + method.toUpperCase() + requestPath + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(preimage.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM but missing here", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("API secret is not a valid HMAC key", e);
        }
    }

    /** The header form OKX expects, and the same string that must appear in the pre-image. */
    public static String timestamp(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }
}
