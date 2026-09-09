package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.engine.EventHandler;
import com.ciaozn.alphatrader.engine.EventPublisher;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 熔断级 (FR-RK-05, DESIGN §8): stops opening new exposure when the account is hurting, on two
 * independent triggers -
 * <ul>
 *   <li><b>daily loss</b>: once equity is down {@code dailyLossFraction} (5%) from the start of the
 *       UTC day, openings are refused for the <em>rest of that day</em> (全天禁止开仓). The latch is
 *       deliberate: a breaker that released as soon as equity bounced back would let a volatile day
 *       flip it on and off, and "down 5% today" is a statement about the day, not the instant.</li>
 *   <li><b>consecutive losses</b>: {@code consecutiveLossLimit} (3) losing trades in a row pause
 *       openings for {@code pause} (2h). A streak that long is the strategy arguing with the market;
 *       the pause is a time-out, not a verdict, so it expires on the clock rather than on a recovery.</li>
 * </ul>
 *
 * <p><b>Only openings are gated.</b> A {@link Direction#FLAT} signal always passes, for the reason
 * every rule in the gate shares: an account that has hit a breaker must still be able to de-risk, and
 * blocking the exit would trap it in the exposure that tripped the rule. {@code LONG} and {@code SHORT}
 * both mean "take on directional exposure" and are refused while a trigger is active. Pre-size there is
 * no quantity, so a flip that would net the book down cannot be told from one that grows it; the
 * conservative choice for a breaker is to refuse and let the strategy FLAT first (same as
 * {@link AccountRule}).
 *
 * <p><b>Two roles, one instance.</b> As a {@link SignalRule} it decides from the {@link SignalFacts} it
 * is handed and never reads the book - so it honours the gate's single-snapshot rule (the facts the gate
 * captured <em>are</em> the account state the decision and the interception record describe). As an
 * {@link EventHandler} on the bus it observes {@link FillEvent}s to keep its state current between
 * signals. This is the one rule that reads the {@link Portfolio}, and only in that observer role: a
 * {@link FillEvent} carries no realized P&L (it is a property of the book, not of the execution), so the
 * per-trade result is recovered as the change in {@link Portfolio#realizedPnl()} since the last fill.
 * That works because the book is updated <em>before</em> the fill is published (取舍 16), and because the
 * engine is single-threaded, so the breaker sees every fill in order with nothing applied in between.
 *
 * <p>The daily-loss latch is re-evaluated at <em>every</em> observation - each fill and each signal -
 * so an intraday trough is caught even if no signal arrives at the trough. Equity is mark-to-market, so
 * a position bleeding unrealized loss trips the breaker without a single fill.
 *
 * <p>Severity follows the gate's one rule: the daily-loss trigger is CRITICAL (a real 5% drawdown is the
 * account in danger) and the losing-streak pause is WARNING (a heuristic time-out; three small losses
 * leave the account fine). The CRITICAL trigger is checked first so a day that is both down 5% and on a
 * losing streak reports the drawdown, not the softer time-out. Runs on the engine thread only, so the
 * mutable state needs no synchronization. Spring-free, and every number it reads is deterministic in
 * backtest (time from the data, equity from the book), so the same inputs give the same verdicts
 * (FR-BT-06) and nothing wall-clock reaches the bit-compared artifacts (NFR-04).
 */
public final class CircuitBreaker implements SignalRule, EventHandler {

    /** The circuit-breaker level's one id; which trigger fired is carried in the detail. */
    public static final String RULE_ID = "RK-05-breaker";

    private final Portfolio portfolio;
    private final BigDecimal dailyLossFraction;
    private final int consecutiveLossLimit;
    private final long pauseMillis;

    // ---- mutable state, engine-thread only ----
    private LocalDate currentDay;            // null until the first observation
    private BigDecimal dayStartEquity;       // equity at the first observation of currentDay
    private boolean dailyLossTripped;        // latched for the UTC day, cleared on rollover
    private BigDecimal lastRealizedPnl;      // cumulative realized P&L as of the last fill seen
    private int consecutiveLosses;
    private long pausedUntilMillis = Long.MIN_VALUE;  // "never paused" until a streak trips it

    /**
     * @param portfolio          the book, read (never written) for equity and cumulative realized P&L
     * @param dailyLossFraction  fraction of day-start equity that trips the daily breaker, e.g. 0.05
     * @param consecutiveLossLimit losing trades in a row that pause openings, e.g. 3
     * @param pause              how long the losing-streak breaker stays tripped, e.g. 2h
     */
    public CircuitBreaker(Portfolio portfolio, BigDecimal dailyLossFraction,
                          int consecutiveLossLimit, Duration pause) {
        if (portfolio == null) {
            throw new IllegalArgumentException("portfolio must not be null: the breaker reads equity"
                    + " and realized P&L off it to observe fills between signals");
        }
        if (dailyLossFraction == null || dailyLossFraction.signum() <= 0) {
            throw new IllegalArgumentException(
                    "dailyLossFraction must be positive, got " + dailyLossFraction
                            + ": a threshold at or below zero would trip on the first tick of a flat day"
                            + " and refuse every opening, which is trading switched off as a 'breaker'");
        }
        if (consecutiveLossLimit < 1) {
            throw new IllegalArgumentException(
                    "consecutiveLossLimit must be >= 1, got " + consecutiveLossLimit
                            + ": 0 or less would pause on the first losing trade and, with a streak that"
                            + " can never reset below it, never resume");
        }
        if (pause == null || pause.isZero() || pause.isNegative()) {
            throw new IllegalArgumentException(
                    "pause must be positive, got " + pause
                            + ": a zero pause trips the breaker and releases it in the same instant,"
                            + " which reads as a breaker that never fired");
        }
        this.portfolio = portfolio;
        this.dailyLossFraction = dailyLossFraction;
        this.consecutiveLossLimit = consecutiveLossLimit;
        this.pauseMillis = pause.toMillis();
        // Start from the book's current cumulative total so fills that happened before this breaker was
        // registered are not attributed to it; only deltas from here on count as trades.
        this.lastRealizedPnl = portfolio.realizedPnl();
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public RiskRule.Level level() {
        return RiskRule.Level.CIRCUIT_BREAKER;
    }

    // ------------------------------------------------------------------ SignalRule: decide

    @Override
    public Optional<RiskRejection> check(SignalFacts facts) {
        long now = facts.nowMillis();
        // Observe first, whatever the direction: a FLAT signal is still an observation of the clock and
        // the equity, so it rolls the UTC day and latches an intraday trough like any other.
        observe(now, facts.equity());

        // De-risking is always allowed: blocking the exit would trap the account in the exposure that
        // tripped the breaker.
        if (facts.signal().direction() == Direction.FLAT) {
            return Optional.empty();
        }

        // CRITICAL first, so a day that is both down 5% and on a losing streak reports the drawdown.
        if (dailyLossTripped) {
            BigDecimal threshold = Money.of(dayStartEquity.multiply(dailyLossFraction, Money.MC));
            return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.CIRCUIT_BREAKER,
                    RiskAlertEvent.Severity.CRITICAL,
                    "daily loss breaker tripped: day-start equity " + dayStartEquity.toPlainString()
                            + ", -" + dailyLossFraction.toPlainString() + " threshold "
                            + threshold.toPlainString() + ", equity now " + facts.equity().toPlainString()
                            + "; openings refused for the rest of " + currentDay + " (UTC)"));
        }
        if (now < pausedUntilMillis) {
            return Optional.of(new RiskRejection(RULE_ID, RiskRule.Level.CIRCUIT_BREAKER,
                    RiskAlertEvent.Severity.WARNING,
                    consecutiveLossLimit + " consecutive losing trades tripped the breaker; openings"
                            + " paused until " + Instant.ofEpochMilli(pausedUntilMillis) + " (now "
                            + Instant.ofEpochMilli(now) + "), signal refused"));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ EventHandler: observe fills

    @Override
    public void onEvent(Event event, EventPublisher publisher) {
        // The breaker never publishes - the gate turns a rejection into a RiskAlertEvent - so the
        // publisher is ignored. It is on the bus only to watch fills.
        if (event instanceof FillEvent fill) {
            onFill(fill);
        }
    }

    /**
     * Folds one execution into the breaker's state. The book is already updated (取舍 16: the fill is
     * applied before it is published), so {@code portfolio.realizedPnl()} is the cumulative total
     * <em>including</em> this fill and the change since the last one is this trade's result.
     */
    public void onFill(FillEvent fill) {
        long now = fill.timestamp();
        observe(now, portfolio.equity());

        BigDecimal realizedNow = portfolio.realizedPnl();
        BigDecimal realized = realizedNow.subtract(lastRealizedPnl);
        lastRealizedPnl = realizedNow;

        if (realized.signum() < 0) {
            consecutiveLosses++;
            if (consecutiveLosses >= consecutiveLossLimit) {
                pausedUntilMillis = now + pauseMillis;
                // Consume the streak: the pause is the consequence, and counting past the limit would
                // only re-trip on expiry. A fresh streak begins after the time-out.
                consecutiveLosses = 0;
            }
        } else if (realized.signum() > 0) {
            // A winning trade ends the streak.
            consecutiveLosses = 0;
        }
        // realized == 0 is an opening, an add or a breakeven close - not a completed losing trade, so
        // the streak is left exactly as it was. An opening between two losses does not reset it.
    }

    // ------------------------------------------------------------------ shared observation

    /**
     * Rolls the UTC day and re-evaluates the daily-loss latch against one equity sample. Called from
     * both roles so the day boundary and an intraday trough are caught at every fill and every signal.
     */
    private void observe(long nowMillis, BigDecimal equityNow) {
        LocalDate day = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC).toLocalDate();
        if (!day.equals(currentDay)) {
            currentDay = day;
            dayStartEquity = equityNow;
            dailyLossTripped = false;  // a new UTC day starts the breaker fresh
        }
        // Latch (do not unlatch): once down the fraction today, openings stay refused until the day
        // rolls, even if equity recovers. A non-positive day-start has no meaningful fraction of it to
        // lose, so the daily trigger is undefined and left to the account rule and the sizer.
        if (!dailyLossTripped && dayStartEquity.signum() > 0) {
            BigDecimal loss = dayStartEquity.subtract(equityNow);
            BigDecimal threshold = Money.of(dayStartEquity.multiply(dailyLossFraction, Money.MC));
            if (loss.compareTo(threshold) >= 0) {
                dailyLossTripped = true;
            }
        }
    }
}
