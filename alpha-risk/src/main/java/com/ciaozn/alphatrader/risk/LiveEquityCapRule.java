package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Live-only cap on account equity (T501, SC-06): above the ceiling, new exposure is refused.
 *
 * <p><b>What it defends against.</b> Position size is a fraction of equity, so the account quietly
 * decides how much money is at risk. Someone tops the account up - a deposit, a profitable month -
 * and every subsequent order is proportionally bigger without anyone choosing that. SC-06 says the
 * live account starts at 1000 USDT and stays there until two clean weeks have passed; this rule is
 * that sentence expressed where it cannot be forgotten.
 *
 * <p><b>Only increasing exposure is blocked.</b> A signal that closes or reduces is always allowed:
 * a guard that prevented getting out of a position because the account was too large would be worse
 * than the condition it guards against. {@link Direction#FLAT} and any signal opposing the current
 * position therefore pass.
 *
 * <p>It is a rule rather than a startup check because equity moves continuously: the account can
 * cross the cap while the process is running, and the moment it does is the moment the next order
 * has to be refused.
 *
 * <p>Not wired into paper mode: a testnet balance is arbitrary (the faucet hands out more than 1000)
 * and blocking on it would make the simulated run unable to trade at all.
 */
public final class LiveEquityCapRule implements SignalRule {

    public static final String RULE_ID = "RK-00-live-cap";

    private final BigDecimal maxEquity;

    public LiveEquityCapRule(BigDecimal maxEquity) {
        if (maxEquity == null || maxEquity.signum() <= 0) {
            throw new IllegalArgumentException("maxEquity must be > 0, got " + maxEquity);
        }
        this.maxEquity = maxEquity;
    }

    public BigDecimal maxEquity() {
        return maxEquity;
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Level level() {
        return Level.ACCOUNT;
    }

    @Override
    public Optional<RiskRejection> check(SignalFacts facts) {
        if (!increasesExposure(facts)) {
            return Optional.empty();
        }
        if (facts.equity().compareTo(maxEquity) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new RiskRejection(RULE_ID, Level.ACCOUNT, RiskAlertEvent.Severity.CRITICAL,
                "account equity " + facts.equity().toPlainString() + " exceeds the live cap "
                        + maxEquity.toPlainString() + " USDT: new exposure refused until the account is"
                        + " brought back under it (SC-06)"));
    }

    private static boolean increasesExposure(SignalFacts facts) {
        if (facts.signal().direction() == Direction.FLAT) {
            return false;
        }
        BigDecimal signed = facts.signedQty();
        int positionSign = signed.signum();
        if (positionSign == 0) {
            return true;
        }
        // Opposing the current position reduces it; matching it adds to it.
        int signalSign = facts.signal().direction() == Direction.LONG ? 1 : -1;
        return signalSign == positionSign;
    }
}
