package com.ciaozn.alphatrader.gateway;

import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.gateway.binance.BackoffPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPoliciesTest {

    @Test
    void backoffGrowsExponentiallyAndCapsAt60s() {
        BackoffPolicy backoff = new BackoffPolicy();
        assertThat(backoff.nextDelayMillis()).isEqualTo(1000);
        assertThat(backoff.nextDelayMillis()).isEqualTo(2000);
        assertThat(backoff.nextDelayMillis()).isEqualTo(4000);
        assertThat(backoff.nextDelayMillis()).isEqualTo(8000);
        assertThat(backoff.nextDelayMillis()).isEqualTo(16000);
        assertThat(backoff.nextDelayMillis()).isEqualTo(32000);
        assertThat(backoff.nextDelayMillis()).isEqualTo(60000);
        assertThat(backoff.nextDelayMillis()).isEqualTo(60000); // capped
    }

    @Test
    void backoffResetsAfterSuccessfulConnection() {
        BackoffPolicy backoff = new BackoffPolicy();
        backoff.nextDelayMillis();
        backoff.nextDelayMillis();
        backoff.reset();
        assertThat(backoff.nextDelayMillis()).isEqualTo(1000);
    }

    @Test
    void timeSyncMeasuresOffsetAndDetectsDrift() {
        VirtualClock clock = new VirtualClock(10_000L);
        // exchange clock 500ms ahead
        TimeSync sync = new TimeSync(() -> 10_500L, clock);
        long offset = sync.calibrateOffset();
        assertThat(offset).isEqualTo(500L);
        assertThat(sync.isDriftExceeded(offset)).isFalse();

        // exchange clock 2s ahead -> drift alarm
        TimeSync drifted = new TimeSync(() -> 12_000L, clock);
        assertThat(drifted.isDriftExceeded(drifted.calibrateOffset())).isTrue();
    }
}
