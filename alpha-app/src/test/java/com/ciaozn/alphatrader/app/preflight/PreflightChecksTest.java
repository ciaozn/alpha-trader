package com.ciaozn.alphatrader.app.preflight;

import com.ciaozn.alphatrader.app.config.OnlineProperties;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreflightChecksTest {

    private static final OnlineProperties.GatewayType BINANCE = OnlineProperties.GatewayType.BINANCE;

    private static PreflightChecks.Inputs healthy(boolean liveProfile, boolean testnet) {
        return new PreflightChecks.Inputs(BINANCE, liveProfile, testnet, true, true, List.of(), true, true);
    }

    private static PreflightChecks.Status statusOf(List<PreflightChecks.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().status();
    }

    @Test
    void aGreenConfigurationPasses() {
        var checks = PreflightChecks.evaluate(healthy(true, false));

        assertThat(checks).allMatch(check -> check.status() == PreflightChecks.Status.PASS
                || check.status() == PreflightChecks.Status.SKIP);
        assertThat(statusOf(checks, "credentials")).isEqualTo(PreflightChecks.Status.PASS);
        assertThat(statusOf(checks, "trading rules")).isEqualTo(PreflightChecks.Status.PASS);
    }

    @Test
    void livePointedAtTestnetFails() {
        // The rehearsal that looks like production: nothing else in the system notices this.
        var checks = PreflightChecks.evaluate(healthy(true, true));

        assertThat(statusOf(checks, "profile/exchange")).isEqualTo(PreflightChecks.Status.FAIL);
        assertThat(PreflightChecks.render(checks)).contains("rehearsal wearing");
    }

    @Test
    void paperPointedAtTheRealExchangeFails() {
        // Real money from a simulated run - the mirror image, and just as silent.
        var checks = PreflightChecks.evaluate(healthy(false, false));

        assertThat(statusOf(checks, "profile/exchange")).isEqualTo(PreflightChecks.Status.FAIL);
        assertThat(PreflightChecks.render(checks)).contains("real orders from a simulated run");
    }

    @Test
    void missingCredentialsOnlyMatterWhenTrading() {
        var marketDataOnly = new PreflightChecks.Inputs(BINANCE, false, true, false, false, List.of(), true, true);
        assertThat(statusOf(PreflightChecks.evaluate(marketDataOnly), "credentials"))
                .isEqualTo(PreflightChecks.Status.SKIP);

        var trading = new PreflightChecks.Inputs(BINANCE, true, false, true, false, List.of(), true, true);
        assertThat(statusOf(PreflightChecks.evaluate(trading), "credentials"))
                .isEqualTo(PreflightChecks.Status.FAIL);
    }

    @Test
    void aSymbolWithoutRulesIsReportedByName() {
        var inputs = new PreflightChecks.Inputs(BINANCE, true, false, true, true,
                List.of(Symbol.parse("BTCUSDT.PERP")), true, true);

        var checks = PreflightChecks.evaluate(inputs);
        assertThat(statusOf(checks, "trading rules")).isEqualTo(PreflightChecks.Status.FAIL);
        assertThat(PreflightChecks.render(checks)).contains("BTCUSDT.PERP");
    }

    @Test
    void alertsEnabledWithoutConfigurationIsAFailure() {
        var inputs = new PreflightChecks.Inputs(BINANCE, true, false, true, true, List.of(), true, false);

        assertThat(statusOf(PreflightChecks.evaluate(inputs), "alerting"))
                .isEqualTo(PreflightChecks.Status.FAIL);
    }

    @Test
    void theSec02ItemsAreAlwaysLeftToAHuman() {
        // The one thing a program cannot check: what the API key is allowed to do on the exchange.
        var checks = PreflightChecks.evaluate(healthy(true, false));

        assertThat(statusOf(checks, "SEC-02 (manual)")).isEqualTo(PreflightChecks.Status.SKIP);
        assertThat(PreflightChecks.render(checks)).contains("withdrawals DISABLED");
    }

    @Test
    void theRenderedChecklistCountsFailures() {
        var broken = new PreflightChecks.Inputs(BINANCE, true, true, true, false,
                List.of(Symbol.parse("BTCUSDT.PERP")), false, false);
        String rendered = PreflightChecks.render(PreflightChecks.evaluate(broken));

        assertThat(rendered).contains("[FAIL]").contains("3 check(s) FAILED");
    }
}
