package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.risk.PositionSizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T302: the five risk levels, their DESIGN §8 numbers, and every startup refusal.
 *
 * <p>Two things are pinned to the same table, from opposite sides, so neither can drift alone. The
 * shipped {@code application.yml} is bound and read back value by value; {@link AlphaProperties.Risk#DEFAULTS}
 * is asserted to hold the identical numbers. An operator who edits the yml sees the bound side move
 * away from §8; a future refactor that changes a code default sees the DEFAULTS side move away from
 * the yml. Only editing both keeps this green, which is the point: these are the account's limits,
 * not free parameters.
 *
 * <p>Every refusal is a number that would not fail loudly on its own. A fraction at or below zero is
 * a rule that never fires (cap too high) or fires on everything (cap too low); a zero pause trips the
 * breaker and releases it in the same instant; a {@code max-orders} of 0 is trading switched off that
 * reports itself only as a stream of alerts. Refusing at bind time turns each into a one-line startup
 * error naming the property, instead of a silent behaviour change discovered in the report.
 *
 * <p>No container: binding the yml directly is the same path {@code @ConfigurationProperties} takes,
 * and {@code BacktestProfileApplicationTest} already covers the full-context acceptance. Binding
 * standalone keeps the refusals testable without booting Spring.
 */
class RiskPropertiesTest {

    // DESIGN §8, restated once here so the bound side and the DEFAULTS side are compared to the same
    // literals rather than to each other (which would agree even if both moved off §8).
    private static final String MAX_LEVERAGE = "3";
    private static final String MIN_MARGIN_RATIO = "1.50";
    private static final String MAX_NOTIONAL_FRACTION = "0.20";
    private static final String MAX_PRICE_DEVIATION = "0.02";
    private static final String MAX_TOTAL_NOTIONAL_FRACTION = "0.60";
    private static final String MAX_SYMBOL_NOTIONAL_FRACTION = "0.30";
    private static final String DAILY_LOSS_FRACTION = "0.05";
    private static final int CONSECUTIVE_LOSSES = 3;
    private static final Duration PAUSE = Duration.ofHours(2);
    private static final int MAX_ORDERS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Binds the shipped {@code application.yml} exactly as {@code @ConfigurationProperties} would. */
    private static AlphaProperties.Risk shippedRisk() {
        StandardEnvironment environment = new StandardEnvironment();
        // Boot's conversion service, so "2h" and "1m" become Durations the way the container does;
        // a plain StandardEnvironment would leave them as strings and fail to bind.
        environment.setConversionService(new ApplicationConversionService());
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            loader.load("application.yml", new ClassPathResource("application.yml"))
                    .forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new IllegalStateException("shipped application.yml is missing from the classpath", e);
        }
        return Binder.get(environment).bindOrCreate("alpha", AlphaProperties.class).risk();
    }

    // ------------------------------------------------------------------ the shipped yml equals DESIGN §8

    @Test
    void theShippedYmlDeclaresTheDesignSection8Limits() {
        AlphaProperties.Risk risk = shippedRisk();

        assertThat(risk.account().enabled()).isTrue();
        assertThat(risk.account().maxLeverage()).isEqualByComparingTo(MAX_LEVERAGE);
        assertThat(risk.account().minMarginRatio()).isEqualByComparingTo(MIN_MARGIN_RATIO);

        assertThat(risk.order().enabled()).isTrue();
        assertThat(risk.order().maxNotionalFraction()).isEqualByComparingTo(MAX_NOTIONAL_FRACTION);
        assertThat(risk.order().maxPriceDeviation()).isEqualByComparingTo(MAX_PRICE_DEVIATION);

        assertThat(risk.portfolio().enabled()).isTrue();
        assertThat(risk.portfolio().maxTotalNotionalFraction()).isEqualByComparingTo(MAX_TOTAL_NOTIONAL_FRACTION);
        assertThat(risk.portfolio().maxSymbolNotionalFraction()).isEqualByComparingTo(MAX_SYMBOL_NOTIONAL_FRACTION);

        assertThat(risk.breaker().enabled()).isTrue();
        assertThat(risk.breaker().dailyLossFraction()).isEqualByComparingTo(DAILY_LOSS_FRACTION);
        assertThat(risk.breaker().consecutiveLosses()).isEqualTo(CONSECUTIVE_LOSSES);
        assertThat(risk.breaker().pause()).isEqualTo(PAUSE);

        assertThat(risk.frequency().enabled()).isTrue();
        assertThat(risk.frequency().maxOrders()).isEqualTo(MAX_ORDERS);
        assertThat(risk.frequency().window()).isEqualTo(WINDOW);
    }

    @Test
    void theShippedYmlLeavesTargetExposureToTheSizerDefault() {
        // target-exposure is declared nowhere, so the binding leaves it null and the effective value
        // is PositionSizer.Policy.DEFAULT - one definition, no second number in yml to drift.
        AlphaProperties.Risk.Sizing sizing = shippedRisk().sizing();
        assertThat(sizing.targetExposure()).isNull();
        assertThat(sizing.effectiveTargetExposure())
                .isEqualByComparingTo(PositionSizer.Policy.DEFAULT.targetExposure());
    }

    // ------------------------------------------------------------------ DEFAULTS equals the same table

    @Test
    void theCodeDefaultsHoldTheSameNumbersAsTheShippedYml() {
        AlphaProperties.Risk defaults = AlphaProperties.Risk.DEFAULTS;

        assertThat(defaults.account().enabled()).isTrue();
        assertThat(defaults.account().maxLeverage()).isEqualByComparingTo(MAX_LEVERAGE);
        assertThat(defaults.account().minMarginRatio()).isEqualByComparingTo(MIN_MARGIN_RATIO);
        assertThat(defaults.order().maxNotionalFraction()).isEqualByComparingTo(MAX_NOTIONAL_FRACTION);
        assertThat(defaults.order().maxPriceDeviation()).isEqualByComparingTo(MAX_PRICE_DEVIATION);
        assertThat(defaults.portfolio().maxTotalNotionalFraction()).isEqualByComparingTo(MAX_TOTAL_NOTIONAL_FRACTION);
        assertThat(defaults.portfolio().maxSymbolNotionalFraction()).isEqualByComparingTo(MAX_SYMBOL_NOTIONAL_FRACTION);
        assertThat(defaults.breaker().dailyLossFraction()).isEqualByComparingTo(DAILY_LOSS_FRACTION);
        assertThat(defaults.breaker().consecutiveLosses()).isEqualTo(CONSECUTIVE_LOSSES);
        assertThat(defaults.breaker().pause()).isEqualTo(PAUSE);
        assertThat(defaults.frequency().maxOrders()).isEqualTo(MAX_ORDERS);
        assertThat(defaults.frequency().window()).isEqualTo(WINDOW);
    }

    @Test
    void anAbsentRiskBlockNormalizesToTheDefaults() {
        // A null Risk (absent from the config) must not switch every level off: each nested block
        // falls back to its DEFAULTS, and each enabled flag normalizes null to true. A primitive
        // boolean would have bound an absent key as false and silently disabled the level.
        AlphaProperties properties = new AlphaProperties("backtest", null, null, true, null, null,
                null, null, null, null, null, null);
        AlphaProperties.Risk risk = properties.risk();

        assertThat(risk).isEqualTo(AlphaProperties.Risk.DEFAULTS);
        assertThat(risk.account().enabled()).isTrue();
        assertThat(risk.order().enabled()).isTrue();
        assertThat(risk.portfolio().enabled()).isTrue();
        assertThat(risk.breaker().enabled()).isTrue();
        assertThat(risk.frequency().enabled()).isTrue();
        assertThat(risk.sizing().targetExposure()).isNull();
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void refusesANonPositiveAccountLimit() {
        assertThatThrownBy(() -> new AlphaProperties.Risk.Account(true, BigDecimal.ZERO, new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-leverage");
        assertThatThrownBy(() -> new AlphaProperties.Risk.Account(true, new BigDecimal("3"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-margin-ratio");
    }

    @Test
    void refusesAnOrderLimitOutsideItsRange() {
        assertThatThrownBy(() -> new AlphaProperties.Risk.Order(true, BigDecimal.ZERO, new BigDecimal("0.02")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-notional-fraction");
        // A deviation above 1 means "any price", i.e. the fat-finger guard switched off.
        assertThatThrownBy(() -> new AlphaProperties.Risk.Order(true, new BigDecimal("0.20"), new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-price-deviation");
    }

    @Test
    void refusesASymbolCapAboveTheTotalCap() {
        // One symbol is part of the total, so a symbol cap looser than the total cap never fires
        // before the total one does - it would be dead configuration that looks like a real limit.
        assertThatThrownBy(() -> new AlphaProperties.Risk.Portfolio(true,
                new BigDecimal("0.30"), new BigDecimal("0.60")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-symbol-notional-fraction");
    }

    @Test
    void refusesABreakerThatCannotResume() {
        assertThatThrownBy(() -> new AlphaProperties.Risk.Breaker(true, new BigDecimal("0.05"), 0, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutive-losses");
        assertThatThrownBy(() -> new AlphaProperties.Risk.Breaker(true, new BigDecimal("0.05"), 3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pause");
        // A loss fraction above 1 can never be reached, so the daily breaker would never fire.
        assertThatThrownBy(() -> new AlphaProperties.Risk.Breaker(true, new BigDecimal("1.5"), 3, PAUSE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-loss-fraction");
    }

    @Test
    void refusesAFrequencyLimitThatIsNotALimit() {
        assertThatThrownBy(() -> new AlphaProperties.Risk.Frequency(true, 0, WINDOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-orders");
        assertThatThrownBy(() -> new AlphaProperties.Risk.Frequency(true, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void refusesATargetExposureAboveTheAccount() {
        assertThatThrownBy(() -> new AlphaProperties.Risk.Sizing(new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target-exposure");
        assertThatThrownBy(() -> new AlphaProperties.Risk.Sizing(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target-exposure");
    }

    @Test
    void refusesAnOrderCapTooTightToEverFlip() {
        // The one inequality spanning two blocks (取舍 17): a flip is a single order of
        // 2 x targetExposure, so an order cap below that blocks every flip and every entry to target
        // while the account keeps the position the strategy already reversed out of.
        AlphaProperties.Risk.Sizing sizing = new AlphaProperties.Risk.Sizing(new BigDecimal("0.30"));
        AlphaProperties.Risk.Order tight = new AlphaProperties.Risk.Order(true,
                new BigDecimal("0.20"), new BigDecimal("0.02"));
        assertThatThrownBy(() -> new AlphaProperties.Risk(
                AlphaProperties.Risk.Account.DEFAULTS, tight, AlphaProperties.Risk.Portfolio.DEFAULTS,
                AlphaProperties.Risk.Breaker.DEFAULTS, AlphaProperties.Risk.Frequency.DEFAULTS, sizing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-notional-fraction");
    }

    @Test
    void acceptsAnOrderCapExactlyTwiceTheTargetExposure() {
        // The boundary is inclusive: 2 x 0.10 == 0.20 is the shipped configuration and must bind.
        AlphaProperties.Risk risk = new AlphaProperties.Risk(
                AlphaProperties.Risk.Account.DEFAULTS, AlphaProperties.Risk.Order.DEFAULTS,
                AlphaProperties.Risk.Portfolio.DEFAULTS, AlphaProperties.Risk.Breaker.DEFAULTS,
                AlphaProperties.Risk.Frequency.DEFAULTS, new AlphaProperties.Risk.Sizing(new BigDecimal("0.10")));
        assertThat(risk.sizing().effectiveTargetExposure()).isEqualByComparingTo("0.10");
    }
}
