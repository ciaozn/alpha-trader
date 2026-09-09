package com.ciaozn.alphatrader.risk;

import java.util.Optional;

/**
 * A rule that can decide from the signal and the account alone (DESIGN §8's account, circuit-breaker
 * and frequency levels). Runs before FR-RK-07, so a rejection here means the signal never even gets
 * sized - which is the point: an account that has hit its daily loss limit should not spend work
 * computing a quantity it will throw away, and the alert should say "circuit breaker", not
 * "order too large".
 */
public interface SignalRule extends RiskRule {

    /** Empty means "this rule does not object"; the first non-empty result stops the pipeline. */
    Optional<RiskRejection> check(SignalFacts facts);
}
