package com.ciaozn.alphatrader.gateway.binance;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinanceSignerTest {

    /**
     * RFC 4231 test case 1: key = 20 × 0x0b, data = "Hi There", expected prefix
     * b0344c61d8db38535ca8afceaf0bf12b... A hard-coded vector from outside this codebase is the only
     * kind of signature test that can fail for the right reason: if our hex encoding or our key
     * handling were wrong, every self-consistent comparison would still pass.
     */
    @Test
    void matchesTheRfc4231Vector() {
        byte[] key = new byte[20];
        java.util.Arrays.fill(key, (byte) 0x0b);
        String secret = new String(key, StandardCharsets.UTF_8);

        assertThat(BinanceSigner.sign("Hi There", secret))
                .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    }

    @Test
    void agreesWithAnIndependentHmacOverAQueryString() {
        String query = "symbol=BTCUSDT&side=BUY&type=MARKET&quantity=0.500&timestamp=1700000000000";
        assertThat(BinanceSigner.sign(query, "s3cr3t")).isEqualTo(independent(query, "s3cr3t"));
    }

    @Test
    void theQueryIsSignedByteForByteIncludingEqualsAndAmpersand() {
        // A signature over a normalised or reordered query would be rejected by the exchange, and
        // the failure would look like a bad key.
        assertThat(BinanceSigner.sign("a=1&b=2", "k")).isNotEqualTo(BinanceSigner.sign("b=2&a=1", "k"));
        assertThat(BinanceSigner.sign("a=1", "k")).isNotEqualTo(BinanceSigner.sign("a=2", "k"));
    }

    @Test
    void refusesAnEmptySecret() {
        assertThatThrownBy(() -> BinanceSigner.sign("x", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BinanceSigner.sign("x", null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String independent(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : raw) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
