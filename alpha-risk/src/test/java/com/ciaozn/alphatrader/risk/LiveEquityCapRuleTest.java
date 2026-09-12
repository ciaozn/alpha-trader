package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveEquityCapRuleTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long NOW = 1_700_000_000_000L;

    private final LiveEquityCapRule rule = new LiveEquityCapRule(new BigDecimal("1000"));

    private static SignalFacts facts(Direction direction, String equity, String signedQty) {
        return new SignalFacts(SignalEvent.of("ma-cross-1", BTC, direction, 1.0, "test", NOW),
                new BigDecimal(equity), new BigDecimal(equity), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(signedQty), new BigDecimal("50000"), NOW);
    }

    @Test
    void anAccountOverTheCapCannotOpenNewExposure() {
        var rejection = rule.check(facts(Direction.LONG, "1500", "0"));

        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(LiveEquityCapRule.RULE_ID);
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
        assertThat(rejection.get().detail()).contains("1500").contains("1000");
    }

    @Test
    void exactlyAtTheCapIsStillAllowed() {
        // "≤ 1000 USDT" is the spec's wording: the boundary belongs to the allowed side.
        assertThat(rule.check(facts(Direction.LONG, "1000", "0"))).isEmpty();
    }

    @Test
    void reducingOrClosingIsAlwaysAllowed() {
        // A guard that trapped a position because the account was too large would be worse than the
        // condition it guards against.
        assertThat(rule.check(facts(Direction.FLAT, "5000", "0.5"))).isEmpty();
        assertThat(rule.check(facts(Direction.SHORT, "5000", "0.5"))).isEmpty();
    }

    @Test
    void addingToAnExistingPositionIsBlocked() {
        assertThat(rule.check(facts(Direction.LONG, "5000", "0.5"))).isPresent();
    }

    @Test
    void reversingThroughZeroIsTreatedAsIncreasing() {
        // Going from long 0.5 to short is a flip: the new exposure is real and larger than flat.
        assertThat(rule.check(facts(Direction.SHORT, "5000", "0.5"))).isEmpty();
        assertThat(rule.check(facts(Direction.SHORT, "5000", "-0.5"))).isPresent();
    }

    @Test
    void refusesANonPositiveCap() {
        assertThatThrownBy(() -> new LiveEquityCapRule(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
