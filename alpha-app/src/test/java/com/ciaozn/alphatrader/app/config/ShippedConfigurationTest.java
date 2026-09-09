package com.ciaozn.alphatrader.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the shipped yml files guarantee about each other (T223). No container: these are the files an
 * operator gets, and the property that has to hold is which file declares what.
 *
 * <p>The invariant is the one {@code DataPlan.store} exists for - the download writes to the store
 * the backtest reads. That is only true while {@code alpha.backtest.data} is declared in the one
 * file both profiles load, because Spring reads {@code application-<profile>.yml} only for the
 * active profile. Two files declaring one directory is how an operator ends up with a full CSV
 * directory and a report with no trades in it, which reads like a bad strategy.
 */
class ShippedConfigurationTest {

    private static final String SHARED = "application.yml";
    private static final String CSV_DIR = "alpha.backtest.data.csv-dir";
    private static final String RISK = "alpha.risk.account.max-leverage";

    @Test
    void theKlineStoreIsDeclaredInTheFileEveryProfileLoads() throws IOException {
        // The declared value, not the bound one: AlphaProperties.Backtest.Data defaults to the same
        // data/klines, so binding would look identical whether the file says so or falls back.
        StandardEnvironment shared = environment(SHARED);
        assertThat(shared.getProperty("alpha.backtest.data.source")).isEqualTo("csv");
        assertThat(shared.getProperty(CSV_DIR)).isEqualTo("data/klines");
    }

    @Test
    void neitherProfileFileDeclaresTheStoreAgain() throws IOException {
        // Asserted on each file alone: with the shared file loaded too, a second declaration of the
        // same directory would be invisible and a different one would only show up as a value.
        assertThat(environment("application-backtest.yml").getProperty(CSV_DIR)).isNull();
        assertThat(environment("application-download.yml").getProperty(CSV_DIR)).isNull();
    }

    @Test
    void bothProfilesResolveTheSameStore() throws IOException {
        assertThat(environment(SHARED, "application-backtest.yml").getProperty(CSV_DIR))
                .isEqualTo(environment(SHARED, "application-download.yml").getProperty(CSV_DIR));
    }

    // ------------------------------------------------------------------ the risk block

    @Test
    void theRiskLimitsAreDeclaredInTheFileEveryProfileLoads() throws IOException {
        // Isomorphism (FR-BT-06) is only real while backtest, paper and live cap and size against
        // the same numbers, and that holds only while alpha.risk lives in the one file all three
        // profiles load. One representative key per level plus the enabled flags: a level quietly
        // dropped from the shared file would fall back to Risk.DEFAULTS and still look configured.
        StandardEnvironment shared = environment(SHARED);
        assertThat(shared.getProperty("alpha.risk.account.max-leverage")).isEqualTo("3");
        assertThat(shared.getProperty("alpha.risk.account.min-margin-ratio")).isEqualTo("1.5");
        assertThat(shared.getProperty("alpha.risk.order.max-notional-fraction")).isEqualTo("0.2");
        assertThat(shared.getProperty("alpha.risk.order.max-price-deviation")).isEqualTo("0.02");
        assertThat(shared.getProperty("alpha.risk.portfolio.max-total-notional-fraction")).isEqualTo("0.6");
        assertThat(shared.getProperty("alpha.risk.portfolio.max-symbol-notional-fraction")).isEqualTo("0.3");
        assertThat(shared.getProperty("alpha.risk.breaker.daily-loss-fraction")).isEqualTo("0.05");
        assertThat(shared.getProperty("alpha.risk.breaker.consecutive-losses")).isEqualTo("3");
        assertThat(shared.getProperty("alpha.risk.breaker.pause")).isEqualTo("2h");
        assertThat(shared.getProperty("alpha.risk.frequency.max-orders")).isEqualTo("10");
        assertThat(shared.getProperty("alpha.risk.frequency.window")).isEqualTo("1m");
        // Declared on purpose, so an operator can see the five levels are on without reading Java.
        assertThat(shared.getProperty("alpha.risk.account.enabled")).isEqualTo("true");
        assertThat(shared.getProperty("alpha.risk.order.enabled")).isEqualTo("true");
        assertThat(shared.getProperty("alpha.risk.portfolio.enabled")).isEqualTo("true");
        assertThat(shared.getProperty("alpha.risk.breaker.enabled")).isEqualTo("true");
        assertThat(shared.getProperty("alpha.risk.frequency.enabled")).isEqualTo("true");
    }

    @Test
    void neitherProfileFileDeclaresRiskAgain() throws IOException {
        // Per file alone, as with the store: with the shared file loaded too a second declaration
        // would be invisible, and a different value would show up only as a number, not as a
        // divergence between the modes that FR-BT-06 forbids.
        assertThat(environment("application-backtest.yml").getProperty(RISK)).isNull();
        assertThat(environment("application-download.yml").getProperty(RISK)).isNull();
    }

    @Test
    void targetExposureIsDeclaredInNoShippedFile() throws IOException {
        // The sizer's exposure has one definition, PositionSizer.Policy.DEFAULT. Declaring it in yml
        // would give it a second that can drift; an empty sizing: node would not even bind. So it is
        // absent everywhere and Risk normalizes the gap to Sizing.DEFAULTS.
        String key = "alpha.risk.sizing.target-exposure";
        assertThat(environment(SHARED).getProperty(key)).isNull();
        assertThat(environment("application-backtest.yml").getProperty(key)).isNull();
        assertThat(environment("application-download.yml").getProperty(key)).isNull();
    }

    /** Later files win, the way a profile-specific file wins over the shared one. */
    private static StandardEnvironment environment(String... resources) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String resource : resources) {
            List<PropertySource<?>> loaded = loader.load(resource, new ClassPathResource(resource));
            loaded.forEach(source -> environment.getPropertySources().addFirst(source));
        }
        return environment;
    }
}
