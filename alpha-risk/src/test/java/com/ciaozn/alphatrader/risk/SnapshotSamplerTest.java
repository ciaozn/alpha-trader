package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotSamplerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");

    private final InMemoryRecordStore records = new InMemoryRecordStore();
    private final VirtualClock clock = new VirtualClock(1_700_000_000_000L);
    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));

    private SnapshotSampler sampler() {
        return new SnapshotSampler(portfolio, records, clock);
    }

    @Test
    void writesEquityAndEveryOpenPositionWithItsMarkPrice() {
        portfolio.mark(BTC, new BigDecimal("50000"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.5"), BigDecimal.ZERO);
        portfolio.mark(BTC, new BigDecimal("51000"));

        sampler().sample();

        assertThat(records.equitySnapshots(0, Long.MAX_VALUE)).hasSize(1);
        assertThat(records.equitySnapshots(0, Long.MAX_VALUE).get(0).businessTs())
                .isEqualTo(clock.nowMillis());
        // Equity is cash + unrealized: 10000 - 0 fee + 0.5 * (51000 - 50000).
        assertThat(records.equitySnapshots(0, Long.MAX_VALUE).get(0).equity())
                .isEqualByComparingTo(new BigDecimal("10500"));

        var positions = records.positions(0, Long.MAX_VALUE);
        assertThat(positions).hasSize(1);
        // The mark price is the row's reason to exist: without it the position cannot be valued later.
        assertThat(positions.get(0).markPrice()).isEqualByComparingTo(new BigDecimal("51000"));
        assertThat(positions.get(0).position().qty()).isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void aClosedPositionIsNotWritten() {
        portfolio.mark(BTC, new BigDecimal("50000"));
        portfolio.applyFill(BTC, Side.BUY, new BigDecimal("50000"), new BigDecimal("0.5"), BigDecimal.ZERO);
        portfolio.applyFill(BTC, Side.SELL, new BigDecimal("50000"), new BigDecimal("0.5"), BigDecimal.ZERO);

        sampler().sample();

        assertThat(records.positions(0, Long.MAX_VALUE)).isEmpty();
        assertThat(records.equitySnapshots(0, Long.MAX_VALUE)).hasSize(1);
    }

    @Test
    void onlyAnswersToItsOwnTimer() {
        SnapshotSampler sampler = sampler();
        sampler.onEvent(TimerEvent.of("something-else", clock.nowMillis()), event -> {
        });

        assertThat(records.equitySnapshots(0, Long.MAX_VALUE)).isEmpty();

        sampler.onEvent(TimerEvent.of(SnapshotSampler.TIMER, clock.nowMillis()), event -> {
        });
        assertThat(records.equitySnapshots(0, Long.MAX_VALUE)).hasSize(1);
    }

    @Test
    void eachSampleIsANewRowAtTheCurrentTime() {
        SnapshotSampler sampler = sampler();
        sampler.sample();
        clock.advanceTo(clock.nowMillis() + 60_000L);
        sampler.sample();

        assertThat(records.equitySnapshots(0, Long.MAX_VALUE)).hasSize(2);
        assertThat(records.equitySnapshots(0, Long.MAX_VALUE).get(1).businessTs())
                .isGreaterThan(records.equitySnapshots(0, Long.MAX_VALUE).get(0).businessTs());
    }
}
