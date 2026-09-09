package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-RK-05 / T306: the two circuit-breaker triggers, each from both sides of its boundary, plus the
 * things the breaker deliberately does not do (gate de-risking, latch a recovered day, carry a pause
 * across the day boundary).
 *
 * <p>The breaker is exercised against a real {@link Portfolio} and a {@link VirtualClock} advanced to
 * fixed instants, because both triggers are about state accrued over a deterministic timeline: the
 * daily loss is mark-to-market against the UTC day's opening equity, and the losing streak is read off
 * the book's cumulative realized P&L as fills land. Numbers are chosen against DESIGN §8's defaults
 * (5% daily, 3 losses, 2h pause) and equity 10000, so the daily boundary lands on a round 500 of loss.
 */
class CircuitBreakerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    /** 2023-11-14T10:00:00Z - mid-morning, so a +2h pause stays inside the same UTC day. */
    private static final long T0 = 1_699_956_000_000L;
    private static final BigDecimal FRACTION = new BigDecimal("0.05");
    private static final int LIMIT = 3;
    private static final Duration PAUSE = Duration.ofHours(2);

    private final VirtualClock clock = new VirtualClock(T0);
    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final CircuitBreaker breaker = new CircuitBreaker(portfolio, FRACTION, LIMIT, PAUSE);

    // ------------------------------------------------------------------ helpers

    /** Applies one execution to the book, then lets the breaker observe it - the production order. */
    private void fill(Side side, String price, String qty) {
        BigDecimal p = new BigDecimal(price);
        BigDecimal q = new BigDecimal(qty);
        BigDecimal fee = Money.zero();
        portfolio.applyFill(BTC, side, p, q, fee);
        breaker.onFill(FillEvent.of("cid", BTC, side, p, q, fee, clock.nowMillis()));
    }

    /** A round trip that realizes -10 x qty: open long at 100, close at 90. */
    private void losingRoundTrip(String qty) {
        fill(Side.BUY, "100", qty);
        fill(Side.SELL, "90", qty);
    }

    /** A round trip that realizes +10 x qty: open long at 90, close at 100. */
    private void winningRoundTrip(String qty) {
        fill(Side.BUY, "90", qty);
        fill(Side.SELL, "100", qty);
    }

    private void tripThePause() {
        losingRoundTrip("1");
        losingRoundTrip("1");
        losingRoundTrip("1");
    }

    private Optional<RiskRejection> check(Direction direction) {
        long now = clock.nowMillis();
        SignalEvent signal = SignalEvent.of("test-strategy", BTC, direction, 1.0, "test", now);
        return breaker.check(SignalFacts.of(signal, portfolio, now));
    }

    private Optional<RiskRejection> checkLong() {
        return check(Direction.LONG);
    }

    // ------------------------------------------------------------------ healthy

    @Test
    void aFreshAccountPassesBothTriggers() {
        // First observation snapshots the day's opening equity; nothing has been lost, no streak.
        assertThat(checkLong()).isEmpty();
    }

    // ------------------------------------------------------------------ daily loss, both sides

    @Test
    void dailyLossExactlyAtTheThresholdTripsAsCritical() {
        fill(Side.BUY, "100", "100");          // day-start equity 10000, position +100 @ 100
        portfolio.mark(BTC, new BigDecimal("95"));  // unrealized -500 -> equity 9500, down exactly 5%
        Optional<RiskRejection> rejection = checkLong();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(CircuitBreaker.RULE_ID);
        assertThat(rejection.get().level()).isEqualTo(RiskRule.Level.CIRCUIT_BREAKER);
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(rejection.get().detail()).contains("daily loss");
    }

    @Test
    void dailyLossOneNotionUnderTheThresholdPasses() {
        fill(Side.BUY, "100", "100");
        portfolio.mark(BTC, new BigDecimal("95.0001"));  // unrealized -499.99 -> equity 9500.01
        assertThat(checkLong()).isEmpty();
    }

    @Test
    void theDailyLatchSurvivesAnEquityRecovery() {
        fill(Side.BUY, "100", "100");
        portfolio.mark(BTC, new BigDecimal("95"));   // trip at -500
        assertThat(checkLong()).isPresent();
        portfolio.mark(BTC, new BigDecimal("98"));   // recover to -200, well inside the threshold
        // "Down 5% today" is a statement about the day: a bounce does not re-open the day for business.
        Optional<RiskRejection> rejection = checkLong();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(rejection.get().detail()).contains("daily loss");
    }

    @Test
    void aDayRolloverClearsTheDailyLatch() {
        fill(Side.BUY, "100", "100");
        portfolio.mark(BTC, new BigDecimal("95"));   // trip on day one
        assertThat(checkLong()).isPresent();
        clock.advanceTo(T0 + Duration.ofDays(1).toMillis());  // a new UTC day
        // The new day re-bases day-start equity to the current 9500, so the loss is 0 and the latch is
        // cleared - the breaker measures each UTC day on its own.
        assertThat(checkLong()).isEmpty();
    }

    // ------------------------------------------------------------------ consecutive losses / pause

    @Test
    void threeConsecutiveLosingTradesPauseOpeningsAsAWarning() {
        tripThePause();
        Optional<RiskRejection> rejection = checkLong();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(CircuitBreaker.RULE_ID);
        assertThat(rejection.get().level()).isEqualTo(RiskRule.Level.CIRCUIT_BREAKER);
        // A losing streak is a heuristic time-out, not the account in danger.
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("consecutive");
    }

    @Test
    void aWinningTradeResetsTheStreakSoItNeverReachesTheLimit() {
        losingRoundTrip("1");
        losingRoundTrip("1");
        winningRoundTrip("1");   // resets the streak to zero
        losingRoundTrip("1");
        losingRoundTrip("1");    // only two in a row again
        assertThat(checkLong()).isEmpty();
    }

    @Test
    void openingsAndAddsRealizeNothingAndDoNotMoveTheStreak() {
        fill(Side.BUY, "100", "1");   // open
        fill(Side.BUY, "100", "1");   // add
        fill(Side.BUY, "90", "1");    // add at a different price
        // No trade has been closed, so nothing has been won or lost: the streak is still zero.
        assertThat(checkLong()).isEmpty();
    }

    @Test
    void thePauseExpiresExactlyAfterTheConfiguredDuration() {
        tripThePause();                       // pausedUntil = T0 + 2h
        assertThat(checkLong()).isPresent();
        clock.advanceTo(T0 + PAUSE.toMillis() - 1);  // one milli short
        assertThat(checkLong()).isPresent();
        clock.advanceTo(T0 + PAUSE.toMillis());      // exactly 2h later -> released
        assertThat(checkLong()).isEmpty();
    }

    @Test
    void thePauseSurvivesADayRolloverButTheDailyLatchDoesNot() {
        // Trip the streak one hour before midnight so the 2h pause straddles the day boundary.
        clock.advanceTo(T0 + Duration.ofHours(13).toMillis());  // 2023-11-14T23:00:00Z
        tripThePause();                                          // pausedUntil = 01:00 next day
        clock.advanceTo(T0 + Duration.ofHours(14).toMillis() + Duration.ofMinutes(30).toMillis());
        // 2023-11-15T00:30:00Z: the day rolled, but the pause is time-based and still active.
        Optional<RiskRejection> rejection = checkLong();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("consecutive").doesNotContain("daily loss");
    }

    // ------------------------------------------------------------------ ordering and exemptions

    @Test
    void theDailyLossIsCheckedFirstSoACriticalDrawdownIsNotMaskedByAWarningPause() {
        // Three losses big enough to also drop the day 5%: -170 each, -510 total.
        losingRoundTrip("17");
        losingRoundTrip("17");
        losingRoundTrip("17");
        Optional<RiskRejection> rejection = checkLong();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(rejection.get().detail()).contains("daily loss").doesNotContain("consecutive");
    }

    @Test
    void aFlatSignalPassesEvenWhenTheDailyBreakerTripped() {
        fill(Side.BUY, "100", "100");
        portfolio.mark(BTC, new BigDecimal("95"));
        assertThat(checkLong()).isPresent();
        // De-risking must always be allowed: blocking the exit traps the account in the losing book.
        assertThat(check(Direction.FLAT)).isEmpty();
    }

    @Test
    void aFlatSignalPassesDuringThePause() {
        tripThePause();
        assertThat(checkLong()).isPresent();
        assertThat(check(Direction.FLAT)).isEmpty();
    }

    @Test
    void aShortOpeningIsGatedTheSameWayAsALong() {
        tripThePause();
        assertThat(check(Direction.SHORT)).isPresent();
    }

    @Test
    void aFreshStreakIsNeededToPauseAgainAfterThePauseExpires() {
        tripThePause();                          // streak consumed by the pause it triggered
        clock.advanceTo(T0 + PAUSE.toMillis());  // the time-out ends
        losingRoundTrip("1");                    // a fresh streak begins at one
        losingRoundTrip("1");                    // ...two, still under the limit of three
        // The streak that fired the breaker was consumed, so two more losses do not re-trip it; without
        // that reset a single later loss would re-pause forever off the old count.
        assertThat(checkLong()).isEmpty();
    }

    // ------------------------------------------------------------------ identity

    @Test
    void reportsTheCircuitBreakerIdAndLevel() {
        assertThat(breaker.ruleId()).isEqualTo("RK-05-breaker");
        assertThat(breaker.level()).isEqualTo(RiskRule.Level.CIRCUIT_BREAKER);
    }

    // ------------------------------------------------------------------ construction guards

    @Test
    void refusesANullPortfolio() {
        assertThatThrownBy(() -> new CircuitBreaker(null, FRACTION, LIMIT, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("portfolio");
    }

    @Test
    void refusesANonPositiveDailyLossFraction() {
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, BigDecimal.ZERO, LIMIT, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyLossFraction");
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, new BigDecimal("-0.05"), LIMIT, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyLossFraction");
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, null, LIMIT, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyLossFraction");
    }

    @Test
    void refusesALessThanOneConsecutiveLossLimit() {
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, FRACTION, 0, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutiveLossLimit");
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, FRACTION, -1, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutiveLossLimit");
    }

    @Test
    void refusesANonPositivePause() {
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, FRACTION, LIMIT, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pause");
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, FRACTION, LIMIT, Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pause");
        assertThatThrownBy(() -> new CircuitBreaker(portfolio, FRACTION, LIMIT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pause");
    }
}
