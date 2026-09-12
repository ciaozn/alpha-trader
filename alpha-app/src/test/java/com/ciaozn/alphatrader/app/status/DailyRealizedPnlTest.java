package com.ciaozn.alphatrader.app.status;

import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventPublisher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Today's realized P&L, where "today" is the UTC day (T402).
 *
 * <p>{@link VirtualClock} makes the day boundary testable at all - midnight is one {@code advanceTo} away,
 * and reproducing the exact instant is what lets these tests say something about order rather than merely
 * about arithmetic. The cases that matter are the ones a plausible-but-wrong implementation gets wrong: a
 * claim invented for a fill from before registration, and a roll that happens late enough to swallow the
 * first fill of the new day.
 */
class DailyRealizedPnlTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    /** 2023-11-14T22:13:20Z - inside one UTC day, nowhere near midnight. */
    private static final long T0 = 1_700_000_000_000L;
    private static final long MIDNIGHT_AFTER_T0 = 1_700_006_400_000L;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private VirtualClock clock;
    private Portfolio portfolio;
    private DailyRealizedPnl daily;

    private void startAt(long millis) {
        clock = new VirtualClock(millis);
        portfolio = new Portfolio(new BigDecimal("10000"));
        daily = new DailyRealizedPnl(portfolio, clock);
    }

    private void fillRoundTrip(BigDecimal entry, BigDecimal exit) {
        // Stands in for what the OMS does earlier in the same round this observer runs in: the book is
        // already updated by the time DailyRealizedPnl sees the event (so that a roll during this same
        // round can still tell yesterday's total from today's first fill).
        portfolio.applyFill(BTC, Side.BUY, entry, BigDecimal.ONE, ZERO);
        portfolio.applyFill(BTC, Side.SELL, exit, BigDecimal.ONE, ZERO);
        daily.onEvent(TimerEvent.of("tick", clock.nowMillis()), (EventPublisher) event -> { });
    }

    @Test
    void theUtcBoundaryIsWhereThisTestThinksItIs() {
        // Every other case's meaning depends on this; pinning it also states the offset assumption
        // rather than leaving it to whoever next touches the constants.
        assertThat(Instant.ofEpochMilli(MIDNIGHT_AFTER_T0).atZone(ZoneOffset.UTC).toString())
                .isEqualTo("2023-11-15T00:00Z");
        assertThat(MIDNIGHT_AFTER_T0).isGreaterThan(T0).isLessThan(T0 + Duration.ofDays(1).toMillis());
    }

    @Test
    void aQuietDayRealizesNothing() {
        startAt(T0);

        assertThat(daily.today()).isEqualByComparingTo("0");
    }

    @Test
    void profitRealizedTodayCountsInFull() {
        startAt(T0);
        fillRoundTrip(new BigDecimal("100"), new BigDecimal("110"));

        assertThat(daily.today()).isEqualByComparingTo("10");
        assertThat(portfolio.realizedPnl()).isEqualByComparingTo("10");
    }

    @Test
    void aRestartMidDayDoesNotClaimEarlierFillsForToday() {
        startAt(T0);
        fillRoundTrip(new BigDecimal("100"), new BigDecimal("110"));

        // This process starts six hours into the same UTC day. Whatever was realized before it woke up
        // belongs to the stretch of history it did not see, and starting from zero here would quietly
        // add it to today's number.
        clock.advanceTo(T0 + Duration.ofHours(6).toMillis());
        DailyRealizedPnl restarted = new DailyRealizedPnl(portfolio, clock);

        assertThat(restarted.today()).isEqualByComparingTo("0");
        assertThat(portfolio.realizedPnl()).isEqualByComparingTo("10");
    }

    @Test
    void aDayNobodyHasObservedYetIsZeroRatherThanYesterdaysTotal() {
        startAt(T0);
        fillRoundTrip(new BigDecimal("100"), new BigDecimal("110"));

        clock.advanceTo(MIDNIGHT_AFTER_T0 + Duration.ofHours(3).toMillis());

        // No event has reached the observer this day, and nothing can be realized without one, so this
        // is not yesterday's profit still showing - it is today having none yet. Subtracting the old
        // baseline here would report 10 against a day that has not traded.
        assertThat(daily.today()).isEqualByComparingTo("0");
        assertThat(portfolio.realizedPnl()).isEqualByComparingTo("10");
    }

    @Test
    void theFirstFillOfTheNewDayCountsEvenThoughItRolledTheDayItself() {
        startAt(T0);
        fillRoundTrip(new BigDecimal("100"), new BigDecimal("110"));

        clock.advanceTo(MIDNIGHT_AFTER_T0);
        fillRoundTrip(new BigDecimal("200"), new BigDecimal("205"));

        // The roll and the fill are the same round - which is precisely the case a lazily-rolled
        // baseline gets wrong: setting it after this fill would report today as zero while the account
        // has just realized five.
        assertThat(daily.today()).isEqualByComparingTo("5");
        assertThat(portfolio.realizedPnl()).isEqualByComparingTo("15");
    }

    @Test
    void thePreviousDayStaysInTheCumulativeTotal() {
        startAt(T0);
        fillRoundTrip(new BigDecimal("100"), new BigDecimal("50"));
        assertThat(daily.today()).isEqualByComparingTo("-50");

        clock.advanceTo(MIDNIGHT_AFTER_T0);
        fillRoundTrip(new BigDecimal("80"), new BigDecimal("100"));

        assertThat(daily.today()).as("yesterday's loss is history, not today's result")
                .isEqualByComparingTo("20");
        assertThat(portfolio.realizedPnl()).isEqualByComparingTo("-30");
    }
}
