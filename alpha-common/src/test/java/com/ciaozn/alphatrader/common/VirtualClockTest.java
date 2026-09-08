package com.ciaozn.alphatrader.common;

import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualClockTest {

    @Test
    void advancesMonotonically() {
        VirtualClock clock = new VirtualClock(1000);
        assertThat(clock.nowMillis()).isEqualTo(1000);

        clock.advanceTo(5000);
        assertThat(clock.nowMillis()).isEqualTo(5000);

        clock.advanceTo(3000); // must not go backwards
        assertThat(clock.nowMillis()).isEqualTo(5000);
    }
}
