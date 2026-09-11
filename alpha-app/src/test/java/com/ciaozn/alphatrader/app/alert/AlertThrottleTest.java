package com.ciaozn.alphatrader.app.alert;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The aggregation rule itself (T401), exercised at instants chosen by the test rather than by waiting.
 *
 * <p>{@code VirtualClock} rather than sleeps for the same reason the rest of the project does it this
 * way: a five-minute window is five thousandths of a second here, and a test that took five minutes per
 * boundary would be a test nobody runs. It also means "inside the window" and "one millisecond past it"
 * are distinguishable, which {@code Thread.sleep} cannot promise.
 */
class AlertThrottleTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private AlertThrottle throttle;
    private VirtualClock clock;

    @BeforeEach
    void setUp() {
        throttle = new AlertThrottle(WINDOW);
        clock = new VirtualClock(T0);
    }

    @Test
    void theFirstInstanceOfARuleIsNotDelayed() {
        // An alert nobody waits five minutes for: deferring the first occurrence would buy a complete
        // count in exchange for the only thing an alert is for.
        assertThat(throttle.evaluate("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();
    }

    @Test
    void repeatsInsideTheWindowAreSwallowed() {
        assertThat(throttle.evaluate("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();

        for (int i = 1; i <= 10; i++) {
            clock.advanceTo(T0 + i * 1_000L);
            assertThat(throttle.evaluate("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                    .as("repeat %d inside the aggregation window", i)
                    .isFalse();
        }
    }

    @Test
    void oneWindowExactlyIsStillInsideIt() {
        assertThat(throttle.evaluate("RK-06-frequency", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();
        clock.advanceTo(T0 + WINDOW.toMillis() - 1);
        assertThat(throttle.evaluate("RK-06-frequency", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .as("the boundary belongs to the window that opened at the first alert")
                .isFalse();
    }

    @Test
    void theFirstAlertAfterTheWindowOpensANewOne() {
        assertThat(throttle.evaluate("RK-06-frequency", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();
        clock.advanceTo(T0 + WINDOW.toMillis());
        assertThat(throttle.evaluate("RK-06-frequency", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();
    }

    @Test
    void theCountOfSwallowedRepeatsRidesOnTheNextMail() {
        assertThat(throttle.evaluate("EX-reconcile-position-mismatch",
                RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send()).isTrue();
        for (int i = 1; i <= 7; i++) {
            clock.advanceTo(T0 + i * 1_000L);
            throttle.evaluate("EX-reconcile-position-mismatch",
                    RiskAlertEvent.Severity.WARNING, clock.nowMillis());
        }

        clock.advanceTo(T0 + WINDOW.toMillis());
        AlertThrottle.Decision decision = throttle.evaluate("EX-reconcile-position-mismatch",
                RiskAlertEvent.Severity.WARNING, clock.nowMillis());

        // Seven collapses did not produce seven mails, and they did not vanish either: the operator
        // learns both that the rule is still firing and that it fired eight times.
        assertThat(decision.send()).isTrue();
        assertThat(decision.collapsed()).isEqualTo(7L);
    }

    @Test
    void theCountIsConsumedAndDoesNotLeakIntoTheWindowAfter() {
        assertThat(throttle.evaluate("RK-05-breaker", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();
        clock.advanceTo(T0 + 1_000L);
        throttle.evaluate("RK-05-breaker", RiskAlertEvent.Severity.WARNING, clock.nowMillis());
        clock.advanceTo(T0 + WINDOW.toMillis());
        assertThat(throttle.evaluate("RK-05-breaker", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).collapsed())
                .isEqualTo(1L);

        clock.advanceTo(T0 + 2 * WINDOW.toMillis());
        assertThat(throttle.evaluate("RK-05-breaker", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).collapsed())
                .as("the previous window's count must not be charged to a later mail")
                .isZero();
    }

    @Test
    void aCriticalIsAlwaysSentAndNeverOpensAWindowThatSilencesOthers() {
        assertThat(throttle.evaluate("EX-exchange-unreachable",
                RiskAlertEvent.Severity.CRITICAL, clock.nowMillis()).send()).isTrue();
        clock.advanceTo(T0 + 1_000L);
        assertThat(throttle.evaluate("EX-exchange-unreachable",
                RiskAlertEvent.Severity.CRITICAL, clock.nowMillis()).send())
                .as("a second critical inside the window is still sent")
                .isTrue();
        assertThat(throttle.evaluate("EX-exchange-unreachable",
                RiskAlertEvent.Severity.CRITICAL, clock.nowMillis()).collapsed())
                .as("criticals are never collapsed into a count")
                .isZero();
    }

    @Test
    void aCriticalDoesNotSuppressTheWarningThatFollowsNorViceVersa() {
        assertThat(throttle.evaluate("RK-05-breaker", RiskAlertEvent.Severity.CRITICAL, clock.nowMillis()).send())
                .isTrue();
        clock.advanceTo(T0 + 1_000L);
        assertThat(throttle.evaluate("RK-05-breaker", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .as("the critical left no window open, so this warning is the first of its own window")
                .isTrue();

        clock.advanceTo(T0 + 2_000L);
        assertThat(throttle.evaluate("RK-05-breaker", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .as("and it did open one, so its own repeat is suppressed")
                .isFalse();
    }

    @Test
    void twoRulesDoNotSilenceEachOther() {
        // Throttling is per rule id on purpose: whoever receives "the breaker tripped" still has to be
        // told that the account rule refused an order a second later.
        assertThat(throttle.evaluate("RK-02-account", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();
        assertThat(throttle.evaluate("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isTrue();

        clock.advanceTo(T0 + 1_000L);
        assertThat(throttle.evaluate("RK-02-account", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isFalse();
        assertThat(throttle.evaluate("RK-03-order", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send())
                .isFalse();
    }

    @Test
    void aWindowThatNeverOpensIsRefused() {
        // Same reasoning as every other positive-duration guard in the configuration: a zero window
        // would collapse every repeat for the lifetime of the process, and the only symptom would be
        // that nobody hears about anything twice.
        assertThatThrownBy(() -> new AlertThrottle(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new AlertThrottle(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAlertWithNoRuleIsSentRatherThanLost() {
        // A nameless alert is a bug in its publisher, but swallowing it here would turn one bug into
        // silence about it. Sending costs one mail and cannot disturb another rule's window.
        assertThat(throttle.evaluate("", RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send()).isTrue();
        assertThat(throttle.evaluate(null, RiskAlertEvent.Severity.WARNING, clock.nowMillis()).send()).isTrue();
    }
}
