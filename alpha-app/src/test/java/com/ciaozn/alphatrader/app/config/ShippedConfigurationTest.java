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
