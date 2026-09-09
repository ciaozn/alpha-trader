package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * 频率级 (FR-RK-06, DESIGN §8): at most {@code maxOrders} (10) signals through the gate inside a
 * sliding {@code window} (1 minute). The level exists for two reasons that are not about the market:
 * the exchange throttles and then bans an account that sends too fast, and a strategy stuck in a loop
 * is far cheaper to stop at the tenth order than at the ten-thousandth.
 *
 * <p><b>One window for the whole gate, not one per symbol.</b> What is being rationed is the account's
 * rate budget at the exchange, and that budget is account-wide: ten symbols each sending one order a
 * second is ten times the traffic of one symbol doing it, and per-symbol windows would let exactly the
 * fan-out burst the rule exists to stop.
 *
 * <p><b>What a slot is.</b> Pre-size there is no quantity yet, so the rule cannot count orders - only
 * the signals that reached it. It therefore counts <em>decisions to trade</em>, which is a slightly
 * conservative proxy: a signal the sizer turns into no-trade, and one the order-stage rules later
 * reject, each consumed a slot they did not spend at the exchange. That is the right side to err on,
 * and it is not free of meaning either - a strategy emitting ten signals a minute that come to nothing
 * is already running away, and the alert saying so is the point of the level.
 *
 * <p><b>A refusal consumes nothing.</b> The slot is taken only when the signal passes, so the counter
 * cannot lock the gate shut: once the oldest slot slides out, the very next signal goes through,
 * however many were refused in between. Counting refusals would turn a one-minute rate limit into a
 * permanent halt.
 *
 * <p><b>De-risking is not exempt here.</b> Every other level in the gate waves a reducing order
 * through, because blocking an exit traps the account in the exposure that tripped the rule. This level
 * is different in kind: it guards a rate budget, and a close order spends that budget exactly like an
 * opening. It also cannot trap anything, because the block is bounded by the window - the refused exit
 * is re-signalled on the next bar and goes through. Exempting FLAT would hand a runaway loop a way to
 * keep sending at full speed just by asking to be flat.
 *
 * <p>Runs last in the signal stage, so it sees only the signals the account and circuit-breaker levels
 * already let through: a day the breaker has closed should not spend its rate budget on refusals, and
 * the alerts should say "breaker", not "too fast".
 *
 * <p>Stateful, and engine-thread only (the gate is the sole caller and runs on the event loop), so the
 * deque needs no synchronization. Deterministic in backtest: the timestamps come from the
 * {@link SignalFacts} the gate built off the {@code VirtualClock}, never from the wall clock, so the
 * same bar sequence gives the same verdicts (FR-BT-06) and nothing wall-clock reaches the bit-compared
 * artifacts (NFR-04).
 */
public final class FrequencyRule implements SignalRule {

    /** The frequency level's one id. */
    public static final String RULE_ID = "RK-06-frequency";

    private final int maxOrders;
    private final long windowMillis;

    /**
     * Timestamp of every signal this rule let through that is still inside the window, oldest first in
     * normal operation. Its size is the number of slots in use.
     */
    private final ArrayDeque<Long> recent = new ArrayDeque<>();

    /**
     * @param maxOrders signals allowed inside the window, e.g. 10
     * @param window    how long a signal keeps its slot, e.g. 1 minute
     */
    public FrequencyRule(int maxOrders, Duration window) {
        if (maxOrders <= 0) {
            throw new IllegalArgumentException(
                    "maxOrders must be >= 1, got " + maxOrders + ": 0 or less is not a rate limit, it is"
                            + " trading switched off, and it would say so only through a stream of alerts");
        }
        // At least a millisecond, not merely positive: the window is counted in epoch millis, so a
        // sub-millisecond window would truncate to zero and evict every slot on arrival, leaving a rule
        // that is configured, logs nothing and never fires.
        if (window == null || window.toMillis() < 1) {
            throw new IllegalArgumentException(
                    "window must be at least 1ms, got " + window + ": a window that never slides would"
                            + " count the first " + maxOrders + " signals of the process and refuse every"
                            + " one after them, forever");
        }
        this.maxOrders = maxOrders;
        this.windowMillis = window.toMillis();
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public RiskRule.Level level() {
        return RiskRule.Level.FREQUENCY;
    }

    @Override
    public Optional<RiskRejection> check(SignalFacts facts) {
        long now = facts.nowMillis();
        // A slot stays while its age is strictly under the window, so the window is (now - window, now]
        // and a signal exactly one window old has slid out.
        long oldestKept = now - windowMillis;
        // Scan every slot rather than drain the head: the deque is ordered in normal operation, but a
        // wall clock that steps backwards (NTP, in live) would leave a future-dated entry at the head,
        // and head-draining would then evict nothing at all - wedging the rule at the limit for good.
        // Scanning costs O(maxOrders), which is ten.
        recent.removeIf(slot -> slot <= oldestKept);

        if (recent.size() >= maxOrders) {
            long freesInMillis = recent.peekFirst() + windowMillis - now;
            return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.FREQUENCY,
                    RiskAlertEvent.Severity.WARNING,
                    recent.size() + " signals passed the gate in the last " + windowMillis + "ms"
                            + " (limit " + maxOrders + "): refused, the oldest slot frees in "
                            + freesInMillis + "ms"));
        }
        // Only a pass takes a slot - see the class javadoc.
        recent.addLast(now);
        return Optional.empty();
    }
}
