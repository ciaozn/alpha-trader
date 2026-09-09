package com.ciaozn.alphatrader.risk;

import java.util.Optional;

/**
 * A rule that needs the quantity FR-RK-07 produced (DESIGN §8's order and portfolio levels). Runs
 * after sizing, so a rejection here blocks a concrete order rather than an intention, and the alert
 * can quote the notional it objected to.
 */
public interface OrderRule extends RiskRule {

    /** Empty means "this rule does not object"; the first non-empty result stops the pipeline. */
    Optional<RiskRejection> check(OrderFacts facts);
}
