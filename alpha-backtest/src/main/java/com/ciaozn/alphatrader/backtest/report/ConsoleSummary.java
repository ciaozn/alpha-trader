package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder;

import java.util.ArrayList;
import java.util.List;

/**
 * The few lines a run prints when it finishes, so the result is visible without opening a file
 * (FR-BT-04's console summary).
 *
 * <p>Unlike {@link HtmlReportRenderer} this one <em>does</em> print wall-clock elapsed time: SC-01
 * is a timing acceptance and the number has to be readable from the run itself. That is safe here
 * precisely because nothing compares this text byte for byte - SC-02's three-runs-identical check
 * is against the HTML report, which deliberately omits it.
 */
public final class ConsoleSummary {

    private ConsoleSummary() {
    }

    public static String render(BacktestReport report) {
        PerformanceMetrics m = report.metrics();
        BacktestDataFeeder.ReplaySummary replay = report.replay();
        StringBuilder out = new StringBuilder(1024);

        out.append("=== 回测完成 ===\n");
        out.append("区间     ").append(ReportFormat.time(replay.firstBusinessTs())).append(" → ")
                .append(ReportFormat.time(replay.lastBusinessTs())).append('\n');
        out.append("数据     ").append(replay.barsReplayed()).append(" 根 / ").append(replay.series())
                .append(" 序列 / ").append(replay.gaps().size()).append(" 处缺口（缺 ")
                .append(replay.missingBars()).append(" 根）\n");
        out.append("权益     ").append(ReportFormat.amount(m.startingEquity())).append(" → ")
                .append(ReportFormat.amount(m.finalEquity())).append("  (")
                .append(ReportFormat.amount(m.netPnl())).append(", ")
                .append(ReportFormat.percent(m.totalReturn())).append(")\n");
        out.append("风险     最大回撤 ").append(ReportFormat.percent(m.maxDrawdown()))
                .append("，夏普 ").append(ReportFormat.ratio(m.sharpe())).append('\n');
        out.append("年化     ").append(ReportFormat.percent(m.annualizedReturn()))
                .append("（短窗口外推，仅供参考）\n");
        out.append("交易     ").append(m.closedTrades()).append(" 笔已平 + ").append(m.openTrades())
                .append(" 笔未平，胜率 ").append(ReportFormat.percent(m.winRate()))
                .append("，盈亏比 ").append(ReportFormat.amount(m.profitFactor())).append('\n');
        out.append("成本     手续费 ").append(ReportFormat.amount(m.totalFees()))
                .append("，资金费 ").append(ReportFormat.amount(report.fundingTotal())).append('\n');
        out.append("耗时     ").append(replay.elapsed().toMillis()).append(" ms（真实时间，不进报告文件）\n");

        List<String> warnings = warnings(report);
        if (warnings.isEmpty()) {
            out.append("未决     无\n");
        } else {
            out.append("未决     ").append(warnings.size()).append(" 项，指标未覆盖全部意图：\n");
            for (String warning : warnings) {
                out.append("  - ").append(warning).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The things that make this run's numbers describe less than the whole picture. Same list the
     * HTML banner shows, kept in one place so the two cannot drift apart.
     */
    private static List<String> warnings(BacktestReport report) {
        List<String> warnings = new ArrayList<>();
        if (report.replay().hasGaps()) {
            warnings.add("输入数据有 " + report.replay().gaps().size() + " 处缺口，共缺 "
                    + report.replay().missingBars() + " 根，缺口期间的策略行为未被检验");
        }
        if (!report.pendingOrders().isEmpty()) {
            warnings.add(report.pendingOrders().size() + " 笔订单到回放结束仍未成交，其盈亏不在任何指标里");
        }
        if (!report.rejections().isEmpty()) {
            warnings.add(report.rejections().size() + " 笔订单被交易所规则拒绝，从未进入市场");
        }
        if (report.metrics().openTrades() > 0) {
            warnings.add(report.metrics().openTrades()
                    + " 笔交易未平仓，盈亏按最后标记价估算且不计入胜率与盈亏比");
        }
        return List.copyOf(warnings);
    }
}
