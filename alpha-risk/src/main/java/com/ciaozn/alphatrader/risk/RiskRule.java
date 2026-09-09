package com.ciaozn.alphatrader.risk;

/**
 * One check in the risk pipeline (FR-RK-01). A rule is a pure decision over facts it is handed:
 * it returns either nothing or a {@link RiskRejection} naming itself, and it never publishes,
 * never sizes and never touches the portfolio - that is the gate's job, so a rule can be
 * unit-tested without an engine and reused unchanged in backtest and live (FR-BT-06).
 *
 * <p><b>Why two sub-interfaces instead of one interface with a stage flag.</b> The order-level and
 * portfolio-level rules cannot be evaluated before FR-RK-07 has produced a quantity, while the
 * account, circuit-breaker and frequency rules cannot usefully be evaluated after it (blocking a
 * signal that was never going to trade wastes nothing but hides the reason). One flat list with a
 * stage field would hand the early rules a context whose quantity is absent, and the check would
 * quietly become a constant - passing everything or rejecting everything - with no test able to
 * tell. Splitting the contract in two makes that state unrepresentable: a {@link SignalRule} cannot
 * read a quantity because it never sees one, and an {@link OrderRule} cannot be registered where no
 * order exists yet.
 *
 * <p>Rules are evaluated in configured order and the first rejection wins, so a rule is only reached
 * when every rule before it passed. That is observable for a rule that keeps state (the frequency
 * counter, the circuit breaker): registering one earlier means it also sees signals a preceding rule
 * would have blocked.
 */
public interface RiskRule {

    /** Stable id reported in the alert, the log line and the interception record (FR-RK-08). */
    String ruleId();

    /** Which of DESIGN §8's levels this check belongs to. */
    Level level();

    /**
     * DESIGN §8's five levels, plus the sizing step. {@code SIZING} is FR-RK-07 rather than one of
     * the five - it is here because a sizing rejection travels the same path out of the gate (alert
     * event plus interception record), and giving it a second shape would mean two block paths to
     * keep in sync. SC-04's "all five levels" means the five above it.
     */
    enum Level {
        ACCOUNT,
        ORDER,
        PORTFOLIO,
        CIRCUIT_BREAKER,
        FREQUENCY,
        SIZING
    }
}
