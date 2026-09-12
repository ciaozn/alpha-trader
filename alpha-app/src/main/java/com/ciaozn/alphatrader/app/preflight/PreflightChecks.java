package com.ciaozn.alphatrader.app.preflight;

import com.ciaozn.alphatrader.app.config.OnlineProperties;
import com.ciaozn.alphatrader.common.model.Symbol;

import java.util.ArrayList;
import java.util.List;

/**
 * The checklist that runs before an online process is allowed to think it is ready (T502, SEC-02).
 *
 * <p><b>Why a checklist and not assertions scattered through the code.</b> Every item here is already
 * enforced somewhere - the gateway refuses orders without credentials, the gate refuses signals
 * without cached rules, {@code EnvValidator} fails the context when keys are missing. What is missing
 * is a single place that says out loud what state the process is in. A run that starts, connects and
 * does nothing looks exactly like a quiet market; the difference shows up hours later as "why did we
 * never trade".
 *
 * <p><b>Two consistency checks are the valuable ones.</b> A {@code live} profile pointed at testnet is
 * a rehearsal that looks like production, and a {@code paper} profile pointed at the real exchange is
 * real money in a simulated run. Both are one property away, both are silent, and neither is caught by
 * any other component - so they are FAIL, not warnings.
 *
 * <p>Nothing here throws: a FAIL is reported loudly and the process continues, because the components
 * that own each guarantee already refuse to act on it. Turning a checklist item into a startup
 * failure would mean one source of truth (the check) overriding another (the component).
 */
public final class PreflightChecks {

    public enum Status {
        PASS,
        FAIL,
        /** Not applicable, or a step only a human can perform (SEC-02's key settings). */
        SKIP
    }

    public record Check(String name, Status status, String detail) {
    }

    public record Inputs(
            OnlineProperties.GatewayType gateway,
            boolean liveProfile,
            boolean testnet,
            boolean tradingEnabled,
            boolean credentialsPresent,
            List<Symbol> symbolsWithoutRules,
            boolean alertEnabled,
            boolean alertConfigured) {

        public Inputs {
            symbolsWithoutRules = List.copyOf(symbolsWithoutRules);
        }
    }

    private PreflightChecks() {
    }

    public static List<Check> evaluate(Inputs in) {
        List<Check> checks = new ArrayList<>();
        checks.add(profileMatchesExchange(in));
        checks.add(credentials(in));
        checks.add(tradingRules(in));
        checks.add(alerting(in));
        checks.add(equityCap(in));
        checks.add(sec02ManualItems(in));
        return List.copyOf(checks);
    }

    private static Check profileMatchesExchange(Inputs in) {
        if (in.liveProfile() && in.testnet()) {
            return new Check("profile/exchange", Status.FAIL,
                    "live profile is pointed at " + in.gateway() + " TESTNET: this is a rehearsal wearing"
                            + " production's clothes. Set alpha.binance-testnet=false for live");
        }
        if (!in.liveProfile() && !in.testnet()) {
            return new Check("profile/exchange", Status.FAIL,
                    "paper profile is pointed at the REAL exchange: real orders from a simulated run."
                            + " Set alpha.binance-testnet=true unless this is intentional");
        }
        return new Check("profile/exchange", Status.PASS,
                (in.liveProfile() ? "live" : "paper") + " + " + in.gateway()
                        + (in.testnet() ? " testnet" : " mainnet"));
    }

    private static Check credentials(Inputs in) {
        if (!in.tradingEnabled()) {
            return new Check("credentials", Status.SKIP,
                    "trading disabled: market data needs no credentials");
        }
        return in.credentialsPresent()
                ? new Check("credentials", Status.PASS, "API credentials present (values never logged)")
                : new Check("credentials", Status.FAIL,
                        "trading enabled but credentials are missing: every order would be refused");
    }

    private static Check tradingRules(Inputs in) {
        if (in.symbolsWithoutRules().isEmpty()) {
            return new Check("trading rules", Status.PASS, "precision rules cached for every configured symbol");
        }
        return new Check("trading rules", Status.FAIL,
                "no cached precision rules for " + in.symbolsWithoutRules() + ": the gate refuses these"
                        + " symbols with a CRITICAL alert (RK-07), so no order can be sized");
    }

    private static Check alerting(Inputs in) {
        if (!in.alertEnabled()) {
            return new Check("alerting", Status.SKIP, "alpha.alert.enabled=false: nobody will be told");
        }
        return in.alertConfigured()
                ? new Check("alerting", Status.PASS, "alert channel configured")
                : new Check("alerting", Status.FAIL,
                        "alerting enabled but the SMTP authorization code is missing: alerts would be"
                                + " logged and never delivered");
    }

    private static Check equityCap(Inputs in) {
        return in.liveProfile()
                ? new Check("equity cap", Status.PASS, "live equity cap armed (SC-06)")
                : new Check("equity cap", Status.SKIP, "live-only guard; a testnet balance is arbitrary");
    }

    private static Check sec02ManualItems(Inputs in) {
        return new Check("SEC-02 (manual)", Status.SKIP,
                in.liveProfile()
                        ? "confirm by hand: API key has withdrawals DISABLED, an IP whitelist bound, and"
                        + " leverage set to 1x on the exchange side"
                        : "not applicable before live");
    }

    /** One line per check, so the startup log is readable without a JSON viewer. */
    public static String render(List<Check> checks) {
        StringBuilder out = new StringBuilder("Preflight checks:\n");
        for (Check check : checks) {
            out.append(String.format("  [%-4s] %-18s %s%n", check.status(), check.name(), check.detail()));
        }
        long failures = checks.stream().filter(c -> c.status() == Status.FAIL).count();
        out.append(failures == 0
                ? "  all checks passed (or skipped with a stated reason)"
                : "  " + failures + " check(s) FAILED - read them before trusting this run");
        return out.toString();
    }
}
