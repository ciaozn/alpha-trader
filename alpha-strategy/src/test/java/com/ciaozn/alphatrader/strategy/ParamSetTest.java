package com.ciaozn.alphatrader.strategy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamSetTest {

    @Test
    void matchesKeysIgnoringCaseDashesAndUnderscores() {
        ParamSet params = new ParamSet("s", Map.of(
                "fast-period", "2", "ALLOW_SHORT", "true", "min_atr_percent", "0.5"));

        assertThat(params.getInt("fastPeriod", 10)).isEqualTo(2);
        assertThat(params.getBoolean("allowShort", false)).isTrue();
        assertThat(params.getDouble("minAtrPercent", 0)).isEqualTo(0.5);
        params.rejectUnknownKeys();
    }

    @Test
    void fallsBackToDefaultsForAbsentKeys() {
        ParamSet params = new ParamSet("s", Map.of("period", " 14 "));

        assertThat(params.getInt("period", 7)).isEqualTo(14);
        assertThat(params.getDouble("threshold", 1.5)).isEqualTo(1.5);
        assertThat(params.getBoolean("loud", true)).isTrue();
        params.rejectUnknownKeys();
    }

    @Test
    void nullConfigurationBehavesLikeEmpty() {
        ParamSet params = new ParamSet("s", null);

        assertThat(params.getInt("period", 7)).isEqualTo(7);
        params.rejectUnknownKeys();
    }

    @Test
    void rejectsKeysNobodyReadAndReportsThemAsWritten() {
        ParamSet params = new ParamSet("ma-cross", Map.of("fastPeriod", "2", "slowPeroid", "3"));
        params.getInt("fastPeriod", 10);

        assertThatThrownBy(params::rejectUnknownKeys)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ma-cross")
                .hasMessageContaining("slowPeroid");
    }

    @Test
    void reportsUnparsableValuesWithStrategyIdAndKey() {
        assertThatThrownBy(() -> new ParamSet("rsi", Map.of("period", "many")).getInt("period", 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rsi").hasMessageContaining("period").hasMessageContaining("not an integer");
        assertThatThrownBy(() -> new ParamSet("rsi", Map.of("oversold", "3O")).getDouble("oversold", 30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oversold").hasMessageContaining("not a number");
        assertThatThrownBy(() -> new ParamSet("rsi", Map.of("loud", "yes")).getBoolean("loud", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loud").hasMessageContaining("true or false");
    }
}
