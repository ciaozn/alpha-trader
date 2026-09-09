package com.ciaozn.alphatrader.app.config;

import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.risk.AccountRule;
import com.ciaozn.alphatrader.risk.CircuitBreaker;
import com.ciaozn.alphatrader.risk.FrequencyRule;
import com.ciaozn.alphatrader.risk.OrderLimitsRule;
import com.ciaozn.alphatrader.risk.OrderRule;
import com.ciaozn.alphatrader.risk.PortfolioRule;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.SignalRule;

import java.util.ArrayList;
import java.util.List;

/**
 * The one mapping from {@code alpha.risk.*} to a rule set (FR-RK-01). Every mode goes through this:
 * backtest hands it to {@code BacktestRunner} as a deferred factory, paper and live build it around
 * their {@code Portfolio} bean. One mapping is what makes FR-BT-06 hold for the rules themselves -
 * three wirings each translating the same yml into a pipeline is three chances for a backtest to run
 * a rule set the live system does not, and the symptom is a backtest that says nothing about trading.
 *
 * <p><b>The order is the contract, not a detail.</b> A rule is reached only if every rule before it
 * passed, so the order decides which rule a blocked signal is attributed to:
 * <ol>
 *   <li><b>account</b> first - it is the hardest stop (leverage over the ceiling is CRITICAL) and the
 *       cheapest, needing only two numbers off the snapshot;</li>
 *   <li><b>breaker</b> second - a day the breaker has closed should not spend the rate budget on
 *       refusals, and the alert should say "breaker" rather than "too fast";</li>
 *   <li><b>frequency</b> last of the signal stage, so its window counts only signals the two above
 *       let through;</li>
 *   <li><b>order</b> then <b>portfolio</b> after sizing: the per-order checks (a fat-finger price is
 *       CRITICAL) before the book-wide ones, so an order that is both mispriced and oversized reports
 *       the mispricing.</li>
 * </ol>
 *
 * <p>A level switched off in yml is absent from the pipeline rather than present and permissive: its
 * rule id then never appears in an alert or an interception record, which is the honest reading of
 * "this level is off" - the alternative, a rule that always passes, would still be listed and still
 * look enforced.
 */
final class RiskPipelines {

    private RiskPipelines() {
    }

    static RiskPipeline of(AlphaProperties.Risk risk, Portfolio portfolio) {
        List<SignalRule> signalRules = new ArrayList<>();
        if (risk.account().enabled()) {
            AlphaProperties.Risk.Account account = risk.account();
            signalRules.add(new AccountRule(account.maxLeverage(), account.minMarginRatio()));
        }
        if (risk.breaker().enabled()) {
            AlphaProperties.Risk.Breaker breaker = risk.breaker();
            signalRules.add(new CircuitBreaker(portfolio, breaker.dailyLossFraction(),
                    breaker.consecutiveLosses(), breaker.pause()));
        }
        if (risk.frequency().enabled()) {
            AlphaProperties.Risk.Frequency frequency = risk.frequency();
            signalRules.add(new FrequencyRule(frequency.maxOrders(), frequency.window()));
        }

        List<OrderRule> orderRules = new ArrayList<>();
        if (risk.order().enabled()) {
            AlphaProperties.Risk.Order order = risk.order();
            orderRules.add(new OrderLimitsRule(order.maxNotionalFraction(), order.maxPriceDeviation()));
        }
        if (risk.portfolio().enabled()) {
            AlphaProperties.Risk.Portfolio positions = risk.portfolio();
            orderRules.add(new PortfolioRule(positions.maxTotalNotionalFraction(),
                    positions.maxSymbolNotionalFraction()));
        }
        return new RiskPipeline(signalRules, orderRules);
    }
}
