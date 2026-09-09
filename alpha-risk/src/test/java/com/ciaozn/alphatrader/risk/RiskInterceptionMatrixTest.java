package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.FillEvent;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SC-04: 五级风控规则每级至少一条「必被拦截」的信号，拦截率 100%，并断言短路点 —— 命中的是哪一条
 * {@code ruleId}、什么严重级、以及（一个 ruleId 下有两个检查时）是哪<em>一个</em>检查。
 *
 * <p>Each row is a whole gate, not a rule in isolation: a real {@link Portfolio}, a real
 * {@link PositionSizer} and all five rules in the shipped order, driven by hand-applied fills and
 * marks. That is what makes the rows evidence about the <em>system</em> rather than about five
 * classes that each pass their own unit test - a rule can be correct and still never be reached,
 * because a rule before it objects first or because FR-RK-07 sizes the signal into a {@code NoTrade}
 * that publishes nothing. The per-rule tests ({@code AccountRuleTest} and the rest) cover the
 * arithmetic; this file covers the wiring, and the two do not share a fixture.
 *
 * <p><b>What a row asserts.</b> No order, exactly one alert, the level's rule id, the severity, and a
 * fragment of the rule's own wording. The wording fragment is the part that identifies the short
 * circuit: {@code RK-02-account} owns both the leverage ceiling (CRITICAL) and the margin floor
 * (WARNING), so id plus severity still leaves two checks, and only the detail says which fired.
 *
 * <p><b>Two rows are honest about reachability rather than contrived to look reachable.</b>
 * <ul>
 *   <li>The <em>order</em> row raises {@code targetExposure} to 0.15, which
 *       {@code AlphaProperties.Risk} refuses to bind (取舍 17 requires
 *       {@code maxNotionalFraction >= 2 x targetExposure}). With the shipped 0.10 the cap cannot be
 *       reached through the gate at all: a full flip is one order of exactly {@code 2 x 0.10 x
 *       equity} and the cap is strict, and every larger order is a reduction, which the cap exempts
 *       on purpose. The row exists because the cap is the enforcement point that makes the coupling
 *       an invariant instead of a hope - and because without it, deleting the cap would fail no test
 *       anywhere in this matrix.</li>
 *   <li>The <em>portfolio</em> row breaches the <b>total</b> cap only. The single-symbol cap (30%)
 *       is unreachable through the gate under any configuration the binder accepts: the sizer's
 *       target is at most {@code targetExposure <= 0.10} of equity, a flip is at most twice that and
 *       is caught by the 20% order cap first, and a position the market has grown past 30% is always
 *       sized <em>down</em>, which {@code OrderFacts.reduces()} exempts. It is covered against
 *       hand-built {@code OrderFacts} in {@code PortfolioRuleTest}; claiming it here would need a
 *       fixture no real gate can produce.</li>
 * </ul>
 *
 * <p>The thresholds below are DESIGN §8's literals retyped, deliberately not read from
 * {@code AlphaProperties.Risk.DEFAULTS}: alpha-risk must not depend on alpha-app, and both sides
 * pinned to the document is what stops a default drifting silently in one of them.
 * {@code RiskPipelinesTest} pins the mapping in alpha-app against the same document.
 */
class RiskInterceptionMatrixTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final TradingRules RULES =
            new TradingRules(BTC, new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("20"));
    private static final long T0 = 1_700_000_000_000L;
    private static final BigDecimal CASH = new BigDecimal("10000");
    private static final String STRATEGY = "ma-cross-btc";

    /** Seven symbols so the portfolio row can fill six of them and be refused on the seventh. */
    private static final List<Symbol> BASKET = List.of(BTC, Symbol.parse("ETHUSDT.PERP"),
            Symbol.parse("SOLUSDT.PERP"), Symbol.parse("BNBUSDT.PERP"), Symbol.parse("XRPUSDT.PERP"),
            Symbol.parse("DOGEUSDT.PERP"), Symbol.parse("ADAUSDT.PERP"));
    private static final TradingRules[] BASKET_RULES = BASKET.stream()
            .map(symbol -> new TradingRules(symbol, new BigDecimal("0.10"), new BigDecimal("0.001"),
                    new BigDecimal("20")))
            .toArray(TradingRules[]::new);

    // ---- DESIGN §8 ----
    private static final BigDecimal MAX_LEVERAGE = new BigDecimal("3");
    private static final BigDecimal MIN_MARGIN_RATIO = new BigDecimal("1.50");
    private static final BigDecimal MAX_ORDER_NOTIONAL_FRACTION = new BigDecimal("0.20");
    private static final BigDecimal MAX_PRICE_DEVIATION = new BigDecimal("0.02");
    private static final BigDecimal MAX_TOTAL_NOTIONAL_FRACTION = new BigDecimal("0.60");
    private static final BigDecimal MAX_SYMBOL_NOTIONAL_FRACTION = new BigDecimal("0.30");
    private static final BigDecimal DAILY_LOSS_FRACTION = new BigDecimal("0.05");
    private static final int CONSECUTIVE_LOSSES = 3;
    private static final Duration PAUSE = Duration.ofHours(2);
    private static final int MAX_ORDERS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** What one signal produced: the order it became, or the alert that explains why it did not. */
    private record Outcome(List<OrderRequestEvent> orders, List<RiskAlertEvent> alerts) {
    }

    /**
     * One row. {@code evidence} is a literal fragment of the rejecting rule's own detail, which is
     * what distinguishes two checks that share a rule id.
     */
    private record Scenario(RiskRule.Level level, String ruleId, RiskAlertEvent.Severity severity,
                            String evidence, String because, Supplier<Outcome> run) {

        @Override
        public String toString() {
            return level + " " + ruleId + " (" + because + ")";
        }
    }

    static Stream<Scenario> matrix() {
        return Stream.of(
                // 账户级: notional 30100 > 3 x equity 10000. The book got there by a hand-applied
                // fill rather than by opening - with a 10% sizer no sequence of signals can build 3x,
                // which is exactly why the ceiling exists as a backstop for a book the market grew.
                new Scenario(RiskRule.Level.ACCOUNT, AccountRule.RULE_ID,
                        RiskAlertEvent.Severity.CRITICAL, "hard leverage ceiling",
                        "总名义 30100 超过 3x 权益 10000 的硬杠杆上限",
                        () -> new Rig(PositionSizer.Policy.DEFAULT, RULES)
                                .mark(BTC, "100")
                                .fill(BTC, Side.BUY, "100", "301")
                                .send(STRATEGY, BTC, Direction.LONG)),

                // 账户级, the softer of its two checks: 25000 is under the 3x ceiling but over the
                // 150% margin floor (equity x 3 = 30000 < 1.5 x 25000 = 37500). Both rows asserting
                // the same id is the point - the id names the level, the detail names the threshold.
                new Scenario(RiskRule.Level.ACCOUNT, AccountRule.RULE_ID,
                        RiskAlertEvent.Severity.WARNING, "margin ratio is under the floor",
                        "保证金率低于 150% 下限（总名义 25000，未触及 3x 上限）",
                        () -> new Rig(PositionSizer.Policy.DEFAULT, RULES)
                                .mark(BTC, "100")
                                .fill(BTC, Side.BUY, "100", "250")
                                .send(STRATEGY, BTC, Direction.LONG)),

                // 熔断级, daily loss: the opening fill seeds day-start equity at 10000, then a 6%
                // adverse mark takes equity to 9400 - a 600 loss against a 500 threshold. The account
                // rule passes this book (9400 x 3 = 28200 > 1.5 x 9400 = 14100), so the alert can only
                // be the breaker's.
                new Scenario(RiskRule.Level.CIRCUIT_BREAKER, CircuitBreaker.RULE_ID,
                        RiskAlertEvent.Severity.CRITICAL, "daily loss breaker tripped",
                        "当日权益自 10000 跌至 9400，超过 -5% 日亏损熔断线",
                        () -> new Rig(PositionSizer.Policy.DEFAULT, RULES)
                                .mark(BTC, "100")
                                .fill(BTC, Side.BUY, "100", "100")
                                .mark(BTC, "94")
                                .send(STRATEGY, BTC, Direction.LONG)),

                // 熔断级, losing streak: three realized losses of 10 each pause openings for 2h. The
                // openings in between realize zero and must not reset the streak, and 30 of total loss
                // is far inside the 500 daily threshold - so this row can only be the WARNING trigger.
                new Scenario(RiskRule.Level.CIRCUIT_BREAKER, CircuitBreaker.RULE_ID,
                        RiskAlertEvent.Severity.WARNING, "consecutive losing trades tripped the breaker",
                        "连续 3 笔亏损触发暂停 2h（日亏损 30 未及熔断线）",
                        () -> new Rig(PositionSizer.Policy.DEFAULT, RULES)
                                .mark(BTC, "100")
                                .fill(BTC, Side.BUY, "100", "10")
                                .mark(BTC, "99").fill(BTC, Side.SELL, "99", "10")
                                .fill(BTC, Side.BUY, "99", "10")
                                .mark(BTC, "98").fill(BTC, Side.SELL, "98", "10")
                                .fill(BTC, Side.BUY, "98", "10")
                                .mark(BTC, "97").fill(BTC, Side.SELL, "97", "10")
                                .send(STRATEGY, BTC, Direction.LONG)),

                // 频率级: ten FLAT signals on a flat book size to NoTrade and publish nothing at all,
                // yet each one takes a slot - a slot is a signal, not an order. The eleventh is
                // refused with the whole rate budget spent and not one order sent.
                new Scenario(RiskRule.Level.FREQUENCY, FrequencyRule.RULE_ID,
                        RiskAlertEvent.Severity.WARNING, "signals passed the gate in the last",
                        "1 分钟窗口内 10 个信号用尽额度，第 11 个被拒",
                        () -> {
                            Rig rig = new Rig(PositionSizer.Policy.DEFAULT, RULES).mark(BTC, "100");
                            for (int slot = 0; slot < MAX_ORDERS; slot++) {
                                rig.send(STRATEGY, BTC, Direction.FLAT);
                            }
                            return rig.send(STRATEGY, BTC, Direction.LONG);
                        }),

                // 订单级: short 15 at 100, then a LONG flip of delta 30 -> notional 3000 against a
                // 2000 cap, and not a reduction (|+15| is not < |-15|). targetExposure 0.15 for the
                // reason in the class javadoc: at the shipped 0.10 this cap is unreachable.
                new Scenario(RiskRule.Level.ORDER, OrderLimitsRule.RULE_ID,
                        RiskAlertEvent.Severity.WARNING, "single-order cap",
                        "反手单名义 3000 超过单笔 20% 权益上限 2000",
                        () -> new Rig(new PositionSizer.Policy(new BigDecimal("0.15")), RULES)
                                .mark(BTC, "100")
                                .fill(BTC, Side.SELL, "100", "15")
                                .send(STRATEGY, BTC, Direction.LONG)),

                // 组合级: six symbols at 1000 each sit exactly on the 60% total cap, so the seventh's
                // own 1000 is legal at every narrower view - 10% of equity, under the 20% order cap,
                // under the 30% symbol cap - and only the projection of the whole book refuses it.
                new Scenario(RiskRule.Level.PORTFOLIO, PortfolioRule.RULE_ID,
                        RiskAlertEvent.Severity.WARNING, "portfolio total cap",
                        "第七个标的使预计总名义 7000 超过 60% 权益上限 6000",
                        () -> {
                            Rig rig = new Rig(PositionSizer.Policy.DEFAULT, BASKET_RULES);
                            BASKET.forEach(symbol -> rig.mark(symbol, "100"));
                            for (int index = 0; index < BASKET.size() - 1; index++) {
                                rig.fill(BASKET.get(index), Side.BUY, "100", "10");
                            }
                            return rig.send(STRATEGY, BASKET.getLast(), Direction.LONG);
                        }));
    }

    // ------------------------------------------------------------------ the matrix

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("matrix")
    void everyLevelHasASignalTheGateMustRefuseAndTheAlertNamesTheRuleThatRefusedIt(Scenario scenario) {
        Outcome outcome = scenario.run().get();

        assertThat(outcome.orders()).as("a refused signal must not also produce an order").isEmpty();
        assertThat(outcome.alerts()).as("exactly one alert, so the interception is unambiguous").hasSize(1);
        RiskAlertEvent alert = outcome.alerts().getFirst();
        // The short circuit: which rule, how bad, and - for a rule with two checks - which check.
        assertThat(alert.ruleId()).isEqualTo(scenario.ruleId());
        assertThat(alert.severity()).isEqualTo(scenario.severity());
        assertThat(alert.detail()).contains(scenario.evidence());
        // FR-RK-08's "当时账户状态" is only actionable if the alert says whose signal was refused.
        assertThat(alert.detail()).contains(STRATEGY);
    }

    @Test
    void theMatrixCoversAllFiveLevelsAndInterceptsEveryRow() {
        List<Scenario> scenarios = matrix().toList();

        // SC-04's 五级. SIZING is FR-RK-07 rather than one of the five (see RiskRule.Level), and it is
        // covered by PositionSizerTest and RiskGateTest - it is a step of the gate, not a level of policy.
        Set<RiskRule.Level> covered = scenarios.stream().map(Scenario::level).collect(Collectors.toSet());
        assertThat(covered).containsExactlyInAnyOrder(RiskRule.Level.ACCOUNT, RiskRule.Level.ORDER,
                RiskRule.Level.PORTFOLIO, RiskRule.Level.CIRCUIT_BREAKER, RiskRule.Level.FREQUENCY);

        long intercepted = scenarios.stream().map(scenario -> scenario.run().get())
                .filter(outcome -> outcome.orders().isEmpty() && outcome.alerts().size() == 1)
                .count();
        assertThat(intercepted).as("拦截率 must be 100%").isEqualTo(scenarios.size());

        // Printed because SC-04 is an acceptance criterion someone has to be able to read off the
        // build log: which level, which rule id, which severity, on what grounds.
        scenarios.forEach(scenario -> System.out.printf("[SC-04] %-16s %-16s %-8s %s%n",
                scenario.level(), scenario.ruleId(), scenario.severity(), scenario.because()));
        System.out.printf("[SC-04] 拦截率 %d/%d = 100%%%n", intercepted, scenarios.size());
    }

    /**
     * When two levels would both object, the one {@code RiskPipelines} put first is the one reported.
     * The book here is over the hard leverage ceiling <em>and</em> the rate window is full, so the
     * alert must name the account level - the hardest and cheapest stop, and the one whose severity is
     * CRITICAL. A gate that ran the rules in another order would still block the signal correctly and
     * would file it under "too fast", which sends whoever reads the alert looking at the wrong thing.
     */
    @Test
    void whenTwoLevelsWouldBothObjectTheFirstInConfiguredOrderIsReported() {
        Rig rig = new Rig(PositionSizer.Policy.DEFAULT, RULES)
                .mark(BTC, "100")
                .fill(BTC, Side.BUY, "100", "301");
        for (int slot = 0; slot < MAX_ORDERS; slot++) {
            rig.send(STRATEGY, BTC, Direction.FLAT);
        }

        Outcome outcome = rig.send(STRATEGY, BTC, Direction.LONG);

        assertThat(outcome.orders()).isEmpty();
        assertThat(outcome.alerts()).hasSize(1);
        assertThat(outcome.alerts().getFirst().ruleId()).isEqualTo(AccountRule.RULE_ID);
        assertThat(outcome.alerts().getFirst().severity()).isEqualTo(RiskAlertEvent.Severity.CRITICAL);
    }

    /**
     * The control. Without it, 拦截率 100% is also what a gate that refuses everything achieves, and a
     * mutation that made one rule unconditional would make this file greener rather than redder.
     */
    @Test
    void aCleanSignalPassesAllFiveLevelsAndBecomesAnOrder() {
        Outcome outcome = new Rig(PositionSizer.Policy.DEFAULT, RULES)
                .mark(BTC, "100")
                .send(STRATEGY, BTC, Direction.LONG);

        assertThat(outcome.alerts()).isEmpty();
        assertThat(outcome.orders()).hasSize(1);
        OrderRequestEvent order = outcome.orders().getFirst();
        assertThat(order.symbol()).isEqualTo(BTC);
        assertThat(order.side()).isEqualTo(Side.BUY);
        assertThat(order.orderType()).isEqualTo(OrderType.MARKET);
        // 10000 x 0.10 / 100, floored to stepSize 0.001.
        assertThat(order.qty()).isEqualByComparingTo("10");
        assertThat(order.price()).isNull();
        assertThat(order.timestamp()).isEqualTo(T0);
    }

    // ------------------------------------------------------------------ the rig

    /**
     * One gate with the full five-rule pipeline, at DESIGN §8's numbers and in the shipped order.
     * Mutable and fluent because a row is a <em>history</em>: the breaker and the frequency window are
     * stateful, and the state they are in when the signal arrives is the row's whole point.
     */
    private static final class Rig {

        private final Portfolio portfolio = new Portfolio(CASH);
        private final VirtualClock clock = new VirtualClock(T0);
        private final CircuitBreaker breaker;
        private final RiskGate gate;
        private long fills;

        Rig(PositionSizer.Policy policy, TradingRules... rules) {
            breaker = new CircuitBreaker(portfolio, DAILY_LOSS_FRACTION, CONSECUTIVE_LOSSES, PAUSE);
            gate = new RiskGate(portfolio, new PositionSizer(policy), FixedTradingRulesProvider.of(rules),
                    // The shipped order, retyped: account, breaker, frequency, then order, portfolio.
                    new RiskPipeline(
                            List.of(new AccountRule(MAX_LEVERAGE, MIN_MARGIN_RATIO), breaker,
                                    new FrequencyRule(MAX_ORDERS, WINDOW)),
                            List.of(new OrderLimitsRule(MAX_ORDER_NOTIONAL_FRACTION, MAX_PRICE_DEVIATION),
                                    new PortfolioRule(MAX_TOTAL_NOTIONAL_FRACTION,
                                            MAX_SYMBOL_NOTIONAL_FRACTION))),
                    clock);
        }

        Rig mark(Symbol symbol, String price) {
            portfolio.mark(symbol, new BigDecimal(price));
            return this;
        }

        /**
         * Applies one execution the way the live path does: the book first, then the bus (取舍 16), so
         * the breaker reads a {@code realizedPnl} that already includes this fill. In the assembled
         * system {@code BacktestRunner.registerRuleObservers} is what puts the breaker on the bus;
         * here it is handed the event directly, and a rule that published from its observer role would
         * be a bug worth failing on.
         */
        Rig fill(Symbol symbol, Side side, String price, String qty) {
            BigDecimal fillPrice = new BigDecimal(price);
            BigDecimal fillQty = new BigDecimal(qty);
            portfolio.applyFill(symbol, side, fillPrice, fillQty, BigDecimal.ZERO);
            breaker.onEvent(FillEvent.of("matrix-fill-" + ++fills, symbol, side, fillPrice, fillQty,
                    BigDecimal.ZERO, clock.nowMillis()),
                    event -> {
                        throw new AssertionError("the breaker observes fills and must publish nothing,"
                                + " got " + event);
                    });
            return this;
        }

        /** Runs one signal through the gate and reports only what that signal produced. */
        Outcome send(String strategyId, Symbol symbol, Direction direction) {
            List<Event> published = new ArrayList<>();
            gate.onEvent(SignalEvent.of(strategyId, symbol, direction, 1.0, "matrix", clock.nowMillis()),
                    published::add);
            return new Outcome(
                    published.stream().filter(OrderRequestEvent.class::isInstance)
                            .map(OrderRequestEvent.class::cast).toList(),
                    published.stream().filter(RiskAlertEvent.class::isInstance)
                            .map(RiskAlertEvent.class::cast).toList());
        }
    }
}
