package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.portfolio.Position;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * Renders a {@link BacktestReport} as one self-contained HTML file (FR-BT-04): inline CSS, inline
 * SVG, no script, no stylesheet link, no font, no charting library, nothing fetched. The report has
 * to open the same offline in six months as it does today, and a CDN dependency would also mean the
 * file's contents are not the whole of what it displays.
 *
 * <p>Pure: it reads nothing but its argument, so the same run renders byte-identically (SC-02).
 * That has one consequence worth stating, because it looks like an omission -
 * {@link BacktestDataFeeder.ReplaySummary#elapsed()} is deliberately <em>not</em> rendered. It is
 * measured with {@code System.nanoTime()} against the wall clock, so putting it in the file would
 * make three runs of the same data differ, which is exactly what SC-02 forbids. The wall-clock
 * timing belongs in the console summary and in the SC-01 acceptance record, where nothing compares
 * it byte for byte.
 *
 * <p>Escaping is a single-emission-point rule: {@code fact}, {@code metricRow} and {@code table}
 * are the only three places text enters markup, each escapes exactly once, and therefore every
 * caller passes raw text. Symbol, client-order-id and rejection-reason text reaches this class from
 * configuration and from data files, so an unescaped value would let a crafted CSV write markup into
 * a report someone opens in a browser. {@code metricRow}'s escaping cannot currently be
 * distinguished by any test - every value routed through it is a formatted number or a literal label
 * - and is kept anyway: dropping it would turn a uniform rule into an exception, and the first
 * caller to route a symbol name through a metric row would inherit the hole silently.
 */
public final class HtmlReportRenderer {

    private HtmlReportRenderer() {
    }

    public static String render(BacktestReport report) {
        StringBuilder html = new StringBuilder(64 * 1024);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>回测报告 ").append(ReportFormat.escape(range(report)))
                .append("</title>\n<style>\n").append(css()).append("\n</style>\n</head>\n<body>\n");

        appendHeader(html, report);
        appendUnresolved(html, report);
        appendMetrics(html, report.metrics());
        appendCurve(html, report);
        appendPositions(html, report.openPositions());
        appendTrades(html, report.trades());
        appendFills(html, report.fills());
        appendFunding(html, report.funding());
        appendRejections(html, report.rejections());
        appendPending(html, report.pendingOrders());
        appendGaps(html, report.replay());
        appendCostModel(html, report.costModel());

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String range(BacktestReport report) {
        return ReportFormat.time(report.replay().firstBusinessTs()) + " → "
                + ReportFormat.time(report.replay().lastBusinessTs());
    }

    private static void appendHeader(StringBuilder html, BacktestReport report) {
        BacktestDataFeeder.ReplaySummary replay = report.replay();
        StringBuilder series = new StringBuilder();
        for (BacktestDataFeeder.Series s : report.series()) {
            if (series.length() > 0) {
                series.append(", ");
            }
            series.append(s.symbol().unified()).append(' ').append(s.interval().binanceCode());
        }
        html.append("<header>\n<h1>回测报告</h1>\n<dl class=\"facts\">\n");
        fact(html, "业务时间区间", range(report));
        fact(html, "回放序列", series.toString());
        fact(html, "回放 K 线", replay.barsReplayed() + " 根 / " + replay.series() + " 个序列");
        fact(html, "采样周期数", Integer.toString(report.metrics().periods()));
        fact(html, "数据缺口", replay.hasGaps()
                ? replay.gaps().size() + " 处，共缺 " + replay.missingBars() + " 根"
                : "无");
        html.append("</dl>\n</header>\n");
    }

    private static void appendUnresolved(StringBuilder html, BacktestReport report) {
        if (!report.hasUnresolved()) {
            return;
        }
        html.append("<section class=\"warn\">\n<h2>本次回测留有未决事项</h2>\n<ul>\n");
        if (report.replay().hasGaps()) {
            html.append("<li>输入数据有缺口：缺口期间的行情不存在，那段时间的策略行为没有被检验。</li>\n");
        }
        if (!report.pendingOrders().isEmpty()) {
            html.append("<li>").append(report.pendingOrders().size())
                    .append(" 笔订单到回放结束仍未成交：信号发出了，但数据在次根开盘之前就用完了，")
                    .append("这些信号的盈亏不在任何指标里。</li>\n");
        }
        if (!report.rejections().isEmpty()) {
            html.append("<li>").append(report.rejections().size())
                    .append(" 笔订单被交易所规则拒绝：它们从未进入市场，也不产生盈亏。</li>\n");
        }
        if (report.metrics().openTrades() > 0) {
            html.append("<li>").append(report.metrics().openTrades())
                    .append(" 笔交易在回放结束时仍未平仓：其盈亏按最后标记价估算，且不计入胜率与盈亏比。</li>\n");
        }
        html.append("</ul>\n</section>\n");
    }

    private static void appendMetrics(StringBuilder html, PerformanceMetrics m) {
        html.append("<section>\n<h2>绩效指标</h2>\n<table class=\"metrics\">\n");
        metricRow(html, "初始权益", ReportFormat.amount(m.startingEquity()),
                "最终权益", ReportFormat.amount(m.finalEquity()));
        metricRow(html, "净盈亏", ReportFormat.amount(m.netPnl()),
                "总收益率", ReportFormat.percent(m.totalReturn()));
        metricRow(html, "年化收益率", ReportFormat.percent(m.annualizedReturn()),
                "夏普比率", ReportFormat.ratio(m.sharpe()));
        metricRow(html, "最大回撤", ReportFormat.percent(m.maxDrawdown()),
                "回撤区间", m.worstDrawdown()
                        .map(d -> ReportFormat.time(d.peakTs()) + " → " + ReportFormat.time(d.troughTs()))
                        .orElse(ReportFormat.UNDEFINED));
        metricRow(html, "已平交易", Integer.toString(m.closedTrades()),
                "未平交易", Integer.toString(m.openTrades()));
        metricRow(html, "胜率", ReportFormat.percent(m.winRate()),
                "盈亏比", ReportFormat.amount(m.profitFactor()));
        metricRow(html, "毛利", ReportFormat.amount(m.grossProfit()),
                "毛损", ReportFormat.amount(m.grossLoss()));
        metricRow(html, "平均赢利", ReportFormat.amount(m.averageWin()),
                "平均亏损", ReportFormat.amount(m.averageLoss()));
        metricRow(html, "赔付比", ReportFormat.amount(m.payoffRatio()),
                "期望值", ReportFormat.amount(m.expectancy()));
        metricRow(html, "最佳交易", ReportFormat.amount(m.bestTrade()),
                "最差交易", ReportFormat.amount(m.worstTrade()));
        metricRow(html, "手续费合计", ReportFormat.amount(m.totalFees()),
                "跨越年数", ReportFormat.fixed(m.yearsElapsed(), 6));
        html.append("</table>\n<p class=\"note\">年化收益率按日历时间几何外推，短窗口下会剧烈放大，仅供参考；")
                .append("阈值断言应使用总收益率与最大回撤。标注「").append(ReportFormat.UNDEFINED)
                .append("」的项是分母为空，不是一个等于零的测量值。</p>\n</section>\n");
    }

    private static void appendCurve(StringBuilder html, BacktestReport report) {
        html.append("<section>\n<h2>净值曲线</h2>\n")
                .append(EquityCurveSvg.render(report.curve(), report.metrics().worstDrawdown()))
                .append("\n</section>\n");
    }

    private static void appendPositions(StringBuilder html, List<Position> positions) {
        html.append("<section>\n<h2>期末持仓</h2>\n");
        table(html, positions, "期末无持仓",
                List.of("标的", "方向", "数量", "开仓均价"),
                p -> List.of(p.symbol().unified(),
                        p.direction().name(),
                        ReportFormat.amount(p.qty()),
                        ReportFormat.amount(p.entryPrice())));
        html.append("</section>\n");
    }

    private static void appendTrades(StringBuilder html, List<Trade> trades) {
        html.append("<section>\n<h2>交易明细（一次 flat→flat 往返为一笔）</h2>\n");
        table(html, trades, "本次回测没有完成任何交易",
                List.of("标的", "方向", "开仓时间", "结束时间", "开仓量", "平仓量",
                        "开仓均价", "平仓均价", "毛盈亏", "手续费", "净盈亏", "状态"),
                t -> List.of(t.symbol().unified(),
                        t.direction() == Trade.Direction.LONG ? "多" : "空",
                        ReportFormat.time(t.openedTs()),
                        ReportFormat.time(t.closedTs()),
                        ReportFormat.amount(t.openedQty()),
                        ReportFormat.amount(t.closedQty()),
                        ReportFormat.amount(t.avgEntryPrice()),
                        t.open() ? ReportFormat.UNDEFINED : ReportFormat.amount(t.avgExitPrice()),
                        ReportFormat.amount(t.grossPnl()),
                        ReportFormat.amount(t.fees()),
                        ReportFormat.amount(t.netPnl()),
                        t.open() ? "未平（按标记价估算）" : "已平"));
        html.append("</section>\n");
    }

    private static void appendFills(StringBuilder html, List<SimulatedExecutor.SimulatedFill> fills) {
        html.append("<section>\n<h2>逐笔成交明细</h2>\n");
        table(html, fills, "本次回测没有任何成交",
                List.of("业务时间", "成交根开盘时间", "标的", "方向", "根开盘价", "滑点(bp)",
                        "成交价", "数量", "手续费", "已实现盈亏", "clientOrderId"),
                f -> List.of(ReportFormat.time(f.businessTs()),
                        ReportFormat.time(f.fillBarOpenTime()),
                        f.symbol().unified(),
                        f.side().name(),
                        ReportFormat.amount(f.barOpen()),
                        ReportFormat.amount(f.slippageBps()),
                        ReportFormat.amount(f.fillPrice()),
                        ReportFormat.amount(f.qty()),
                        ReportFormat.amount(f.fee()),
                        ReportFormat.amount(f.realizedPnl()),
                        f.clientOrderId()));
        html.append("</section>\n");
    }

    private static void appendFunding(StringBuilder html, List<SimulatedExecutor.FundingSettlement> funding) {
        html.append("<section>\n<h2>资金费结算（UTC 对齐 8h 边界）</h2>\n");
        table(html, funding, "本次回测没有资金费结算",
                List.of("结算边界", "标的", "资金费率", "持仓数量", "标记价", "计费"),
                s -> List.of(ReportFormat.time(s.boundaryMillis()),
                        s.symbol().unified(),
                        ReportFormat.amount(s.fundingRate()),
                        ReportFormat.amount(s.signedQty()),
                        ReportFormat.amount(s.markPrice()),
                        ReportFormat.amount(s.charge())));
        html.append("</section>\n");
    }

    private static void appendRejections(StringBuilder html, List<SimulatedExecutor.Rejection> rejections) {
        html.append("<section>\n<h2>被拒订单</h2>\n");
        table(html, rejections, "没有订单被拒绝",
                List.of("业务时间", "标的", "clientOrderId", "原因"),
                r -> List.of(ReportFormat.time(r.businessTs()),
                        r.symbol().unified(),
                        r.clientOrderId(),
                        r.reason()));
        html.append("</section>\n");
    }

    private static void appendPending(StringBuilder html, List<OrderRequestEvent> pending) {
        html.append("<section>\n<h2>未成交订单（回放结束时仍挂着）</h2>\n");
        table(html, pending, "没有遗留的未成交订单",
                List.of("业务时间", "标的", "方向", "类型", "数量", "clientOrderId"),
                o -> List.of(ReportFormat.time(o.timestamp()),
                        o.symbol().unified(),
                        o.side().name(),
                        o.orderType().name(),
                        ReportFormat.amount(o.qty()),
                        o.clientOrderId()));
        html.append("</section>\n");
    }

    private static void appendGaps(StringBuilder html, BacktestDataFeeder.ReplaySummary replay) {
        html.append("<section>\n<h2>数据缺口</h2>\n");
        table(html, replay.gaps(), "输入数据连续，没有缺口",
                List.of("标的", "周期", "缺口起点（openTime）", "缺失根数"),
                g -> List.of(g.symbol().unified(),
                        g.interval().binanceCode(),
                        ReportFormat.time(g.expectedOpenTime()),
                        Long.toString(g.missingBars())));
        html.append("</section>\n");
    }

    private static void appendCostModel(StringBuilder html, SimulatedExecutor.CostModel cost) {
        html.append("<section>\n<h2>成本模型</h2>\n<table class=\"metrics\">\n");
        metricRow(html, "taker 手续费率", ReportFormat.amount(cost.takerFeeRate()),
                "固定滑点(bp)", ReportFormat.amount(cost.fixedSlippageBps()));
        metricRow(html, "振幅滑点系数", ReportFormat.amount(cost.amplitudeFactor()),
                "每 8h 资金费率", ReportFormat.amount(cost.fundingRatePerInterval()));
        html.append("</table>\n<p class=\"note\">滑点取自下单那根 K 线的振幅，偏置一律对交易者不利。</p>\n</section>\n");
    }

    private static void fact(StringBuilder html, String label, String value) {
        html.append("<dt>").append(ReportFormat.escape(label)).append("</dt><dd>")
                .append(ReportFormat.escape(value)).append("</dd>\n");
    }

    private static void metricRow(StringBuilder html, String labelA, String valueA,
                                  String labelB, String valueB) {
        html.append("<tr><th>").append(ReportFormat.escape(labelA)).append("</th><td>")
                .append(ReportFormat.escape(valueA)).append("</td><th>")
                .append(ReportFormat.escape(labelB)).append("</th><td>")
                .append(ReportFormat.escape(valueB)).append("</td></tr>\n");
    }

    /**
     * One generic table. An empty list renders a sentence saying so rather than a header with no
     * rows: "no trades" and "the trades failed to load" must not look the same.
     */
    private static <T> void table(StringBuilder html, List<T> rows, String emptyText,
                                  List<String> headers, Function<T, List<String>> cells) {
        if (rows.isEmpty()) {
            html.append("<p class=\"empty\">").append(ReportFormat.escape(emptyText)).append("</p>\n");
            return;
        }
        html.append("<table>\n<thead><tr>");
        for (String header : headers) {
            html.append("<th>").append(ReportFormat.escape(header)).append("</th>");
        }
        html.append("</tr></thead>\n<tbody>\n");
        for (T row : rows) {
            List<String> values = cells.apply(row);
            if (values.size() != headers.size()) {
                throw new IllegalStateException("Report table column mismatch: " + headers.size()
                        + " headers but " + values.size() + " cells");
            }
            html.append("<tr>");
            for (String value : values) {
                html.append("<td>").append(ReportFormat.escape(value)).append("</td>");
            }
            html.append("</tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private static String css() {
        return """
                :root { color-scheme: light dark; }
                body { font: 14px/1.5 -apple-system, "Helvetica Neue", Arial, "PingFang SC", \
                "Microsoft YaHei", sans-serif; margin: 0; padding: 24px; color: #1a1a1a; background: #fff; }
                h1 { font-size: 22px; margin: 0 0 12px; }
                h2 { font-size: 16px; margin: 28px 0 8px; padding-bottom: 4px; border-bottom: 1px solid #ddd; }
                section { margin-bottom: 8px; }
                dl.facts { display: grid; grid-template-columns: max-content 1fr; gap: 2px 16px; margin: 0; }
                dl.facts dt { color: #666; }
                dl.facts dd { margin: 0; }
                table { border-collapse: collapse; width: 100%; font-variant-numeric: tabular-nums; }
                th, td { border: 1px solid #ddd; padding: 4px 8px; text-align: right; white-space: nowrap; }
                th { background: #f5f5f5; font-weight: 600; }
                thead th { position: sticky; top: 0; }
                table.metrics th { text-align: left; width: 14%; color: #666; font-weight: 400; }
                table.metrics td { text-align: left; width: 36%; font-weight: 600; }
                tbody tr:nth-child(even) { background: #fafafa; }
                p.empty { color: #666; font-style: italic; margin: 4px 0; }
                p.note { color: #666; font-size: 12px; margin: 6px 0 0; }
                section.warn { border: 1px solid #e0a800; background: #fff8e1; padding: 8px 16px; \
                border-radius: 4px; margin-top: 16px; }
                section.warn h2 { border: 0; margin: 4px 0; color: #8a6d00; }
                section.warn ul { margin: 4px 0 8px; padding-left: 20px; }
                svg.curve { width: 100%; height: auto; display: block; }
                svg .plot { fill: #fcfcfc; stroke: #ccc; }
                svg .grid { stroke: #eee; }
                svg .baseline { stroke: #999; stroke-dasharray: 4 3; }
                svg .equity { fill: none; stroke: #1f6feb; stroke-width: 1.5; }
                svg .dot { fill: #1f6feb; }
                svg .drawdown { stroke: #d1242f; stroke-dasharray: 3 3; }
                svg text { font: 11px -apple-system, Arial, sans-serif; fill: #666; }
                svg .baselinelabel, svg .drawdownlabel { fill: #444; }
                svg .drawdownlabel { fill: #d1242f; }
                """;
    }
}
