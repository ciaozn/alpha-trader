package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.gateway.okx.OkxSigner;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class OkxSignerTest {

    /**
     * The formula OKX documents, spelled out as its own example does:
     * {@code Base64(HmacSHA256(timestamp + 'GET' + '/api/v5/account/balance?ccy=BTC', secret))}.
     *
     * <p>The expectation is re-derived here from javax.crypto rather than copied from production
     * code, and the pre-image is written out by hand - so this test fails if the part order, the
     * base64 encoding or the inclusion of the query string is wrong.
     */
    @Test
    void followsTheDocumentedFormulaIncludingTheQueryString() {
        String preimage = "2020-12-08T09:08:57.715Z" + "GET" + "/api/v5/account/balance?ccy=BTC";

        assertThat(OkxSigner.sign("2020-12-08T09:08:57.715Z", "GET", "/api/v5/account/balance?ccy=BTC", "",
                "22582BD0CFF14C41EDBF1AB98506286D"))
                .isEqualTo(independent(preimage, "22582BD0CFF14C41EDBF1AB98506286D"));
    }

    /**
     * The regression that matters: an earlier version excluded the query from the pre-image, which
     * OKX answers with 50113 "signature invalid" - an error that reads like a wrong key. The query is
     * signed, so two requests differing only in their query must sign differently.
     */
    @Test
    void theQueryStringChangesTheSignature() {
        String withQuery = OkxSigner.sign("t", "GET", "/api/v5/account/positions?instType=SWAP", "", "k");
        String withoutQuery = OkxSigner.sign("t", "GET", "/api/v5/account/positions", "", "k");

        assertThat(withQuery).isNotEqualTo(withoutQuery);
        assertThat(withQuery).isEqualTo(independent("t" + "GET" + "/api/v5/account/positions?instType=SWAP", "k"));
    }

    @Test
    void thePreimageIsTimestampMethodPathBodyInThatOrder() {
        String expected = independent("2026-09-12T02:30:00.000Z" + "POST" + "/api/v5/trade/order"
                + "{\"instId\":\"BTC-USDT-SWAP\"}", "secret");
        assertThat(OkxSigner.sign("2026-09-12T02:30:00.000Z", "POST", "/api/v5/trade/order",
                "{\"instId\":\"BTC-USDT-SWAP\"}", "secret")).isEqualTo(expected);
    }

    @Test
    void timestampIsIsoUtcWithMilliseconds() {
        assertThat(OkxSigner.timestamp(1_700_000_000_000L)).isEqualTo("2023-11-14T22:13:20.000Z");
    }

    private static String independent(String preimage, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(preimage.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
