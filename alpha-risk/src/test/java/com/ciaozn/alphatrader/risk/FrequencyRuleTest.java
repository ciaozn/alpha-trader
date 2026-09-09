package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-RK-06 / T307: the sliding window from both sides of its edge - a burst is refused, the refusal
 * costs nothing, and the gate reopens exactly when the oldest slot ages out, one slot at a time.
 *
 * <p>The limit is 3 in a 1-minute window rather than DESIGN §8's 10, so the boundaries are reachable in
 * a handful of assertions; the shape of the window does not depend on the number. Timestamps are fixed
 * epoch millis handed straight to {@link SignalFacts}, because the rule reads nothing else - no book, no
 * clock of its own - which is what makes it deterministic in backtest (FR-BT-06).
 */
class FrequencyRuleTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final Symbol ETH = Symbol.parse("ETHUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final long WINDOW_MILLIS = 60_000L;

    private final FrequencyRule rule = new FrequencyRule(LIMIT, WINDOW);

    // ------------------------------------------------------------------ helpers

    /** The rule reads only {@code nowMillis}; the account fields are filler. */
    private static SignalFacts factsAt(Symbol symbol, Direction direction, long now) {
        SignalEvent signal = SignalEvent.of("test-strategy", symbol, direction, 1.0, "test", now);
        return new SignalFacts(signal, new BigDecimal("10000"), new BigDecimal("10000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100"), now);
    }

    private Optional<RiskRejection> at(long now) {
        return rule.check(factsAt(BTC, Direction.LONG, now));
    }

    private Optional<RiskRejection> at(Symbol symbol, Direction direction, long now) {
        return rule.check(factsAt(symbol, direction, now));
    }

    /** Fills the window with three passes at T0, leaving nothing free. */
    private void fillTheWindow() {
        assertThat(at(T0)).isEmpty();
        assertThat(at(T0)).isEmpty();
        assertThat(at(T0)).isEmpty();
    }

    // ------------------------------------------------------------------ 突发拦截

    @Test
    void aBurstOverTheLimitIsRefusedAsAWarning() {
        fillTheWindow();

        Optional<RiskRejection> rejection = at(T0);
        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(FrequencyRule.RULE_ID);
        assertThat(rejection.get().level()).isEqualTo(RiskRule.Level.FREQUENCY);
        // A rate limit is policy, not danger: the account is fine, this signal was merely too fast.
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("limit 3").contains("3 signals");
    }

    @Test
    void aSteadyRateUnderTheLimitIsNeverBlocked() {
        // One signal every 30s for ten of them: two slots in use at any instant, so a cumulative
        // counter would have refused the fourth and the window is what keeps this passing.
        for (int i = 0; i < 10; i++) {
            assertThat(at(T0 + i * 30_000L)).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 窗口滑动

    @Test
    void aSignalExactlyOneWindowOldHasSlidOut() {
        fillTheWindow();

        // One millisecond inside the window: the oldest slot is still held.
        assertThat(at(T0 + WINDOW_MILLIS - 1)).isPresent();
        // Exactly one window old it is gone, so the gate reopens - the window is (now - window, now].
        assertThat(at(T0 + WINDOW_MILLIS)).isEmpty();
    }

    @Test
    void theWindowSlidesOneSlotAtATimeNotAllAtOnce() {
        assertThat(at(T0)).isEmpty();
        assertThat(at(T0 + 1_000)).isEmpty();
        assertThat(at(T0 + 2_000)).isEmpty();

        Optional<RiskRejection> rejection = at(T0 + 30_000);
        assertThat(rejection).isPresent();
        // The oldest slot, not the newest, is the one that frees the gate - and it does so in 30s.
        assertThat(rejection.get().detail()).contains("frees in 30000ms");

        assertThat(at(T0 + WINDOW_MILLIS)).isEmpty();      // only T0 aged out: one slot, not three
        assertThat(at(T0 + WINDOW_MILLIS)).isPresent();    // T0+1000 and T0+2000 are still inside
    }

    @Test
    void aRefusalConsumesNoSlotSoTheGateReopensOnTime() {
        fillTheWindow();
        assertThat(at(T0 + 10_000)).isPresent();
        assertThat(at(T0 + 10_000)).isPresent();

        // Had the two refusals taken slots, only one signal would fit here and the gate would stay
        // shut behind it - a one-minute rate limit turning into a halt.
        long reopen = T0 + WINDOW_MILLIS;
        assertThat(at(reopen)).isEmpty();
        assertThat(at(reopen)).isEmpty();
        assertThat(at(reopen)).isEmpty();
        assertThat(at(reopen)).isPresent();
    }

    // ------------------------------------------------------------------ what is counted

    @Test
    void aFlatSignalSpendsTheRateBudgetLikeAnyOther() {
        // The one level that does not exempt de-risking: a close order costs the same rate budget as an
        // opening, and the block expires with the window instead of trapping the position.
        assertThat(at(BTC, Direction.FLAT, T0)).isEmpty();
        assertThat(at(BTC, Direction.FLAT, T0)).isEmpty();
        assertThat(at(BTC, Direction.FLAT, T0)).isEmpty();
        assertThat(at(BTC, Direction.FLAT, T0)).isPresent();
        // And the budget is shared: an opening behind the FLAT burst is refused too.
        assertThat(at(BTC, Direction.LONG, T0)).isPresent();
    }

    @Test
    void oneWindowCoversEverySymbolSoAFanOutBurstIsRefused() {
        // What is rationed is the account's rate budget at the exchange, which is account-wide: per-symbol
        // windows would let a ten-symbol fan-out through at ten times the intended rate.
        assertThat(at(BTC, Direction.LONG, T0)).isEmpty();
        assertThat(at(ETH, Direction.SHORT, T0)).isEmpty();
        assertThat(at(BTC, Direction.SHORT, T0)).isEmpty();
        assertThat(at(ETH, Direction.LONG, T0)).isPresent();
    }

    // ------------------------------------------------------------------ identity

    @Test
    void reportsTheFrequencyIdAndLevel() {
        assertThat(rule.ruleId()).isEqualTo("RK-06-frequency");
        assertThat(rule.level()).isEqualTo(RiskRule.Level.FREQUENCY);
    }

    // ------------------------------------------------------------------ construction guards

    @Test
    void refusesANonPositiveOrderLimit() {
        assertThatThrownBy(() -> new FrequencyRule(0, WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOrders");
        assertThatThrownBy(() -> new FrequencyRule(-1, WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOrders");
    }

    @Test
    void refusesAWindowShorterThanAMillisecond() {
        // Truncating to zero millis would evict every slot on arrival: a rule that is configured,
        // never fires and never says so.
        assertThatThrownBy(() -> new FrequencyRule(LIMIT, Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new FrequencyRule(LIMIT, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new FrequencyRule(LIMIT, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        assertThatThrownBy(() -> new FrequencyRule(LIMIT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }
}
