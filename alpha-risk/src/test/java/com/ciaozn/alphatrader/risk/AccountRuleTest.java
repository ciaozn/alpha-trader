package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-RK-02 / T303: the two account thresholds, each tested from both sides of its boundary, plus
 * what the rule deliberately does <em>not</em> gate (de-risking, a flat book, no equity).
 *
 * <p>The numbers are chosen against DESIGN §8's defaults (maxLeverage 3x, minMarginRatio 150%) and
 * equity 10000, so the two boundaries land on round notionals: the margin floor binds at
 * {@code totalNotional > 20000} (because {@code equity x maxLeverage = 30000} and
 * {@code 1.5 x 20000 = 30000}) and the leverage ceiling at {@code totalNotional > 30000}. Because the
 * ceiling is checked first, a book over 30000 reports leverage (CRITICAL) even though the margin floor
 * is also breached - asserted explicitly so the ordering cannot silently swap.
 */
class AccountRuleTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final BigDecimal EQUITY = new BigDecimal("10000");

    /** DESIGN §8 defaults: 3x ceiling, 150% floor. */
    private final AccountRule rule = new AccountRule(new BigDecimal("3"), new BigDecimal("1.50"));

    /** The rule reads only equity, totalNotional and the signal's direction; the rest is filler. */
    private static SignalFacts facts(Direction direction, String equity, String totalNotional) {
        SignalEvent signal = SignalEvent.of("ma-cross-btc", BTC, direction, 1.0, "test", T0);
        return new SignalFacts(signal, new BigDecimal(equity), new BigDecimal(equity),
                new BigDecimal(totalNotional), new BigDecimal(totalNotional),
                BigDecimal.ONE, new BigDecimal("100"), T0);
    }

    private static SignalFacts opening(String equity, String totalNotional) {
        return facts(Direction.LONG, equity, totalNotional);
    }

    // ------------------------------------------------------------------ healthy book

    @Test
    void aHealthyBookPassesBothThresholds() {
        // leverage 1x (10000/10000), margin ratio 3x (30000/10000): both comfortably inside.
        assertThat(rule.check(opening("10000", "10000"))).isEmpty();
    }

    // ------------------------------------------------------------------ margin floor, both sides

    @Test
    void marginRatioExactlyAtTheFloorPasses() {
        // equity x maxLeverage = 30000 == 1.50 x 20000: at the floor, not under it, so it passes.
        assertThat(rule.check(opening("10000", "20000"))).isEmpty();
    }

    @Test
    void marginRatioOneNotionUnderTheFloorIsRefusedAsAWarning() {
        Optional<RiskRejection> rejection = rule.check(opening("10000", "20000.01")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(AccountRule.RULE_ID);
        assertThat(rejection.get().level()).isEqualTo(RiskRule.Level.ACCOUNT);
        // The early warning, not the hard stop: leverage here is only ~2x, under the 3x ceiling.
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("margin ratio");
    }

    // ------------------------------------------------------------------ leverage ceiling, both sides

    @Test
    void leverageExactlyAtTheCeilingIsNotALeverageBreach() {
        // At totalNotional 30000 the ceiling itself is not breached (30000 is not > 30000), so the
        // rejection that does come out is the margin floor's - proving the leverage check passed here.
        Optional<RiskRejection> rejection = rule.check(opening("10000", "30000")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("margin ratio");
    }

    @Test
    void leverageOneNotionOverTheCeilingIsRefusedAsCriticalAndCheckedBeforeMargin() {
        Optional<RiskRejection> rejection = rule.check(opening("10000", "30000.01")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(AccountRule.RULE_ID);
        assertThat(rejection.get().level()).isEqualTo(RiskRule.Level.ACCOUNT);
        // Over 3x both thresholds are breached; the ceiling is checked first, so it - not the softer
        // margin floor - is what the interception record names.
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(rejection.get().detail()).contains("leverage ceiling");
    }

    // ------------------------------------------------------------------ what is never gated

    @Test
    void aFlatSignalPassesEvenWhenTheAccountIsOverTheCeiling() {
        // De-risking must always be allowed: blocking the exit would trap the account in the very
        // exposure that tripped the rule.
        assertThat(rule.check(facts(Direction.FLAT, "10000", "99000"))).isEmpty();
    }

    @Test
    void aShortOpeningIsGatedTheSameWayAsALong() {
        // The rule gates "take on directional exposure", not one side of it.
        assertThat(rule.check(facts(Direction.SHORT, "10000", "30000.01"))).isPresent();
    }

    @Test
    void aFlatBookIsHealthyAndPasses() {
        // Zero notional means zero leverage and an infinite margin ratio, whatever the equity.
        assertThat(rule.check(opening("10000", "0"))).isEmpty();
    }

    @Test
    void noEquityIsLeftToTheSizerRatherThanDoubleClaimed() {
        // equity <= 0 is PositionSizer.RULE_NO_EQUITY; the account rule passes it through so one
        // condition does not get two rule ids in the interception record.
        assertThat(rule.check(opening("0", "5000"))).isEmpty();
        assertThat(rule.check(opening("-100", "5000"))).isEmpty();
    }

    // ------------------------------------------------------------------ construction guards

    @Test
    void refusesANonPositiveLeverageCeiling() {
        assertThatThrownBy(() -> new AccountRule(BigDecimal.ZERO, new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLeverage");
        assertThatThrownBy(() -> new AccountRule(new BigDecimal("-3"), new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLeverage");
        assertThatThrownBy(() -> new AccountRule(null, new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLeverage");
    }

    @Test
    void refusesANonPositiveMarginFloor() {
        assertThatThrownBy(() -> new AccountRule(new BigDecimal("3"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minMarginRatio");
        assertThatThrownBy(() -> new AccountRule(new BigDecimal("3"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minMarginRatio");
    }
}
