package com.ciaozn.alphatrader.gateway.binance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Binance request signing: HMAC-SHA256 of the query string, hex encoded (T316).
 *
 * <p>A pure function on purpose - no client, no clock, no configuration. The signature is the one
 * thing about a signed request that a test can check exactly: a test that re-derives the HMAC from
 * the same secret and compares bytes catches a wrong encoding, a wrong key order or a missing
 * parameter, whereas "the exchange accepted it" catches nothing until production.
 */
public final class BinanceSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private BinanceSigner() {
    }

    public static String sign(String payload, String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("API secret is required to sign a request");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return hex(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JVM but missing here", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("API secret is not a valid HMAC key", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
