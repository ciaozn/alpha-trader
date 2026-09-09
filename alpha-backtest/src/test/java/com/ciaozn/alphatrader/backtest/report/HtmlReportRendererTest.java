package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.backtest.feed.BacktestDataFeeder;
import com.ciaozn.alphatrader.backtest.match.SimulatedExecutor;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.OrderType;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Position;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report is the artifact a human actually reads, so these tests assert on what it says rather
 * than on how it is built. Two properties get the most attention because both fail silently:
 * self-containment (a CDN reference still renders fine today and breaks the day the network or the
 * library goes away) and determinism (SC-02 compares three runs byte for byte, and one wall-clock
 * stamp anywhere in the file defeats it while looking perfectly reasonable).
 *
 * <p>Metric values are produced by {@link PerformanceAnalyzer} from a real fixture and then looked
 * for in the output, so this class tests the rendering and never re-derives a formula.
 */
class HtmlReportRendererTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_006_400_000L;
    private static final long HOUR = 3_600_000L;
    private static final Duration SAMPLE = Duration.ofHours(1);

    // ------------------------------------------------------------------ self-containment

    @Test
    void theReportFetchesNothingFromAnywhere() {
        String html = HtmlReportRenderer.render(report());

        // Anything the browser would have to go and get. `url(` catches CSS-referenced images and
        // fonts; `//` alone would false-positive on comments, so the protocol-relative form is
        // checked as it would actually appear in a src or href.
        for (String banned : List.of("<script", "<link", "@import", "src=", "href=", "url(",
                "http:", "https:", "<iframe", "<img", "<object", "<embed")) {
            assertThat(html).as("the report must not reference %s", banned).doesNotContain(banned);
        }
        assertThat(html).contains("<style>", "<svg class=\"curve\"");
    }

    @Test
    void theWholeDocumentIsUtf8AndDeclaresIt() {
        String html = HtmlReportRenderer.render(report());

        assertThat(html).contains("<meta charset=\"utf-8\">", "<!DOCTYPE html>");
        // The Chinese labels are the point of the assertion: a file written in the platform's
        // default charset would mojibake them on any machine that is not already UTF-8.
        assertThat(html).contains("回测报告", "绩效指标", "净值曲线", "逐笔成交明细");
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void theSameRunRendersByteIdentically() {
        assertThat(HtmlReportRenderer.render(report()))
                .isEqualTo(HtmlReportRenderer.render(report()));
    }

    @Test
    void theReportDoesNotFollowTheMachineLocale() {
        // An unqualified String.format("%,.2f") prints 1,234.56 here and 1.234,56 under a German
        // default locale, and ar-SA substitutes the digits themselves. SC-02's three-runs-identical
        // check passes on any one machine and breaks the day the run moves, so pin it here.
        String expected = HtmlReportRenderer.render(report());
        String expectedConsole = ConsoleSummary.render(report());
        Locale original = Locale.getDefault();
        try {
            for (String tag : List.of("de-DE", "ar-SA", "fr-FR")) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                assertThat(HtmlReportRenderer.render(report())).as("html, locale %s", tag)
                        .isEqualTo(expected);
                assertThat(ConsoleSummary.render(report())).as("console, locale %s", tag)
                        .isEqualTo(expectedConsole);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void timestampsDoNotShiftWithTheMachineTimezone() {
        // Business time is exchange time. Rendered in the machine's zone, the same run would print
        // different hours on a laptop in two timezones and the report would no longer be reproducible.
        String expected = HtmlReportRenderer.render(report());
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("Pacific/Kiritimati", "Pacific/Pago_Pago")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(HtmlReportRenderer.render(report())).as("zone %s", zone).isEqualTo(expected);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void wallClockElapsedStaysOutOfTheReportFile() {
        // ReplaySummary.elapsed comes from System.nanoTime, so it differs on every run. SC-02
        // compares the HTML across three runs; printing this would fail that check while the
        // numbers themselves stayed perfectly reproducible.
        String html = HtmlReportRenderer.render(report());

        assertThat(html).doesNotContain("耗时").doesNotContain("1234 ms");
        assertThat(ConsoleSummary.render(report())).contains("1234 ms");
    }

    // ------------------------------------------------------------------ content

    @Test
    void everyHeadlineMetricIsPrintedWithItsValue() {
        BacktestReport report = report();
        PerformanceMetrics m = report.metrics();
        String html = HtmlReportRenderer.render(report);

        assertThat(html)
                .contains("初始权益").contains(ReportFormat.amount(m.startingEquity()))
                .contains("最终权益").contains(ReportFormat.amount(m.finalEquity()))
                .contains("净盈亏").contains(ReportFormat.amount(m.netPnl()))
                .contains("总收益率").contains(ReportFormat.percent(m.totalReturn()))
                .contains("年化收益率").contains(ReportFormat.percent(m.annualizedReturn()))
                .contains("夏普比率").contains(ReportFormat.ratio(m.sharpe()))
                .contains("最大回撤").contains(ReportFormat.percent(m.maxDrawdown()))
                .contains("胜率").contains(ReportFormat.percent(m.winRate()))
                .contains("盈亏比").contains(ReportFormat.amount(m.profitFactor()))
                .contains("手续费合计").contains(ReportFormat.amount(m.totalFees()));
    }

    @Test
    void undefinedStatisticsAreNamedInsteadOfBeingPrintedAsZero() {
        // No closed trades, so win rate, profit factor and payoff ratio have empty denominators.
        // The curve dips, so max drawdown is a real non-zero measurement and must not be conflated
        // with them: "undefined" and "measured zero" have to look different in the output.
        EquityCurve curve = curve("10000", "10400", "9600", "10200");
        BacktestReport report = report(List.of(), curve, false);
        String html = HtmlReportRenderer.render(report);

        assertThat(report.metrics().winRate()).isEmpty();
        assertThat(report.metrics().profitFactor()).isEmpty();
        assertThat(report.metrics().payoffRatio()).isEmpty();
        assertThat(report.metrics().maxDrawdown()).isNotZero();

        assertThat(html)
                .contains("<th>胜率</th><td>" + ReportFormat.UNDEFINED + "</td>")
                .contains("<th>盈亏比</th><td>" + ReportFormat.UNDEFINED + "</td>")
                .contains("<th>赔付比</th><td>" + ReportFormat.UNDEFINED + "</td>")
                .contains("<th>最大回撤</th><td>"
                        + ReportFormat.percent(report.metrics().maxDrawdown()) + "</td>");
    }

    @Test
    void emptySectionsSaySoRatherThanShowingABareHeader() {
        String html = HtmlReportRenderer.render(report(List.of(), curve("10000", "10100"), false));

        assertThat(html).contains("本次回测没有任何成交", "没有订单被拒绝", "期末无持仓",
                "没有遗留的未成交订单");
    }

    @Test
    void tradesFillsFundingAndGapsEachGetARow() {
        BacktestReport report = report();
        String html = HtmlReportRenderer.render(report);

        assertThat(html).contains("已平").contains("未平（按标记价估算）");
        assertThat(html).contains("co-long-1").contains("co-short-2");
        assertThat(html).contains("资金费结算").contains("数据缺口").contains("缺口起点");
        // The gap's missing-bar count is the number a reader acts on, so pin the cell, not a digit
        // that appears in half the coordinates in the document.
        assertThat(html).contains("<td>3</td>");
    }

    // ------------------------------------------------------------------ unfinished business

    @Test
    void unfinishedBusinessIsSurfacedRatherThanBuried() {
        BacktestReport report = report();
        String html = HtmlReportRenderer.render(report);

        assertThat(report.hasUnresolved()).isTrue();
        assertThat(html).contains("本次回测留有未决事项");
        assertThat(html).contains("仍未成交").contains("被交易所规则拒绝")
                .contains("仍未平仓").contains("输入数据有缺口");
    }

    @Test
    void aCleanRunShowsNoWarningBanner() {
        BacktestReport report = cleanReport();
        String html = HtmlReportRenderer.render(report);

        assertThat(report.hasUnresolved()).isFalse();
        assertThat(html).doesNotContain("本次回测留有未决事项");
        assertThat(html).contains("没有遗留的未成交订单", "没有订单被拒绝");
    }

    @Test
    void theConsoleSummaryListsTheSameUnresolvedItems() {
        String console = ConsoleSummary.render(report());

        assertThat(console).contains("未决").contains("仍未成交").contains("被交易所规则拒绝")
                .contains("未平仓").contains("缺口");
        assertThat(ConsoleSummary.render(cleanReport())).contains("未决     无");
    }

    // ------------------------------------------------------------------ escaping

    @Test
    void markupArrivingFromDataIsEscapedNotInterpreted() {
        // reason and clientOrderId are free-form strings that originate in configuration and data
        // files; an unescaped value would let a crafted input write markup into the report.
        List<SimulatedExecutor.Rejection> rejections = List.of(
                new SimulatedExecutor.Rejection("<script>alert(1)</script>", BTC,
                        "min & \"max\" < 0", T0));
        BacktestReport report = new BacktestReport(replay(false), series(), metrics(),
                curve("10000", "10100"), List.of(), List.of(), List.of(), rejections, List.of(),
                List.of(), SimulatedExecutor.CostModel.DEFAULT);

        String html = HtmlReportRenderer.render(report);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("min &amp; &quot;max&quot; &lt; 0");
        // Escaped exactly once: a double escape would show the entity itself to the reader.
        assertThat(html).doesNotContain("&amp;lt;").doesNotContain("&amp;amp;");
    }

    // ------------------------------------------------------------------ the curve

    @Test
    void aFlatSingleSampleDrawsADotInsteadOfAnInvisiblePolyline() {
        String html = HtmlReportRenderer.render(report(List.of(), curve("10000", "10000"), false));

        assertThat(html).contains("<circle class=\"dot\"");
        assertThat(html).doesNotContain("<polyline");
    }

    @Test
    void theCurveIsDownsampledButTheFinalSampleIsNeverSteppedOver() {
        int n = 5000;
        BigDecimal start = Money.of("10000");
        // Same low, high, starting equity, first and last timestamp as the two-point curve below,
        // so both renders share one scale and one x-axis: their last vertex must be identical.
        List<EquityCurve.Point> many = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            many.add(new EquityCurve.Point(T0 + i * HOUR, start));
        }
        long lastTs = T0 + (n - 1) * HOUR;
        many.add(new EquityCurve.Point(lastTs, Money.of("20000")));
        String dense = polylineOf(renderCurve(new EquityCurve(start, many)));

        String sparse = polylineOf(renderCurve(new EquityCurve(start, List.of(
                new EquityCurve.Point(T0, start), new EquityCurve.Point(lastTs, Money.of("20000"))))));

        assertThat(vertices(dense)).hasSizeLessThan(2002);
        assertThat(lastVertex(dense)).isEqualTo(lastVertex(sparse));
        assertThat(lastVertex(dense)).isNotEqualTo(firstVertex(dense));
    }

    @Test
    void theWorstDrawdownIsMarkedOnTheCurve() {
        String html = renderCurve(curve("10000", "10400", "9600", "10200"));
        PerformanceMetrics metrics = PerformanceAnalyzer.analyze(
                curve("10000", "10400", "9600", "10200"), List.of(), SAMPLE);

        assertThat(metrics.worstDrawdown()).isPresent();
        assertThat(html).contains("class=\"drawdown\"").contains("最大回撤");
    }

    @Test
    void theAxisLabelsKeepEnoughDecimalsToStayDistinctOnATinyEquityRange() {
        List<String> tiny = yLabels(EquityCurveSvg.render(curve("0.001", "0.001", "0.0012"),
                Optional.empty()));

        // At zero decimals all five of these round to "0" and the axis says nothing at all.
        assertThat(tiny).hasSize(5).doesNotHaveDuplicates();

        List<String> wide = yLabels(EquityCurveSvg.render(curve("10000", "10000", "12000"),
                Optional.empty()));
        assertThat(wide).hasSize(5).doesNotHaveDuplicates().noneMatch(label -> label.contains("."));
    }

    // ------------------------------------------------------------------ helpers

    private static String renderCurve(EquityCurve curve) {
        return EquityCurveSvg.render(curve,
                PerformanceAnalyzer.analyze(curve, List.of(), SAMPLE).worstDrawdown());
    }

    private static final Pattern POLYLINE = Pattern.compile("<polyline class=\"equity\" points=\"([^\"]*)\"");

    private static String polylineOf(String svg) {
        Matcher matcher = POLYLINE.matcher(svg);
        assertThat(matcher.find()).as("expected a polyline in %s", svg).isTrue();
        return matcher.group(1);
    }

    private static List<String> vertices(String polyline) {
        return Arrays.stream(polyline.trim().split("\\s+")).filter(s -> !s.isEmpty()).toList();
    }

    private static String firstVertex(String polyline) {
        return vertices(polyline).get(0);
    }

    private static String lastVertex(String polyline) {
        List<String> vertices = vertices(polyline);
        return vertices.get(vertices.size() - 1);
    }

    private static final Pattern YLABEL = Pattern.compile("<text class=\"ylabel\"[^>]*>([^<]*)</text>");

    private static List<String> yLabels(String svg) {
        Matcher matcher = YLABEL.matcher(svg);
        List<String> labels = new ArrayList<>();
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }
        return labels;
    }

    private static BacktestReport report() {
        return report(trades(), curve("10000", "10100", "10050", "10300", "10250"), true);
    }

    private static BacktestReport report(List<Trade> trades, EquityCurve curve, boolean unresolved) {
        return new BacktestReport(replay(unresolved), series(),
                PerformanceAnalyzer.analyze(curve, trades, SAMPLE), curve, trades,
                unresolved ? fills() : List.of(),
                unresolved ? funding() : List.of(),
                unresolved ? rejections() : List.of(),
                unresolved ? pending() : List.of(),
                unresolved ? positions() : List.of(),
                SimulatedExecutor.CostModel.DEFAULT);
    }

    private static BacktestReport cleanReport() {
        List<Trade> trades = List.of(trade(Trade.Direction.LONG, "12.50", "1.50", false));
        EquityCurve curve = curve("10000", "10100", "10200");
        return new BacktestReport(replay(false), series(),
                PerformanceAnalyzer.analyze(curve, trades, SAMPLE), curve, trades,
                fills(), funding(), List.of(), List.of(), List.of(),
                SimulatedExecutor.CostModel.DEFAULT);
    }

    private static PerformanceMetrics metrics() {
        return PerformanceAnalyzer.analyze(curve("10000", "10100"), trades(), SAMPLE);
    }

    private static List<BacktestDataFeeder.Series> series() {
        return List.of(new BacktestDataFeeder.Series(BTC, Interval.H1));
    }

    private static BacktestDataFeeder.ReplaySummary replay(boolean withGap) {
        List<BacktestDataFeeder.Gap> gaps = withGap
                ? List.of(new BacktestDataFeeder.Gap(BTC, Interval.H1, T0 + 10 * HOUR, 3))
                : List.of();
        long missing = withGap ? 3 : 0;
        // 1234 ms is asserted absent from the HTML and present in the console summary.
        return new BacktestDataFeeder.ReplaySummary(1, 24, missing, gaps, T0, T0 + 23 * HOUR,
                Duration.ofMillis(1234));
    }

    private static EquityCurve curve(String startingEquity, String... equities) {
        List<EquityCurve.Point> points = new ArrayList<>();
        for (int i = 0; i < equities.length; i++) {
            points.add(new EquityCurve.Point(T0 + i * HOUR, Money.of(equities[i])));
        }
        return new EquityCurve(Money.of(startingEquity), points);
    }

    private static List<Trade> trades() {
        return List.of(
                trade(Trade.Direction.LONG, "120.00", "1.50", false),
                trade(Trade.Direction.SHORT, "-40.00", "1.50", false),
                trade(Trade.Direction.LONG, "10.00", "1.00", true));
    }

    private static Trade trade(Trade.Direction direction, String netPnl, String fees, boolean open) {
        BigDecimal fee = Money.of(fees);
        BigDecimal net = Money.of(netPnl);
        return new Trade(BTC, direction, T0, T0 + 5 * HOUR, Money.of("1"), Money.of("1"),
                Money.of("10000"), Money.of("10120"), net.add(fee), fee, net, open);
    }

    private static List<SimulatedExecutor.SimulatedFill> fills() {
        return List.of(
                new SimulatedExecutor.SimulatedFill("co-long-1", BTC, Side.BUY, T0 + HOUR,
                        Money.of("10000"), Money.of("5.5"), Money.of("10005.5"), Money.of("0.1"),
                        Money.of("0.5"), Money.zero(), T0 + HOUR),
                new SimulatedExecutor.SimulatedFill("co-short-2", BTC, Side.SELL, T0 + 2 * HOUR,
                        Money.of("10100"), Money.of("6"), Money.of("10094"), Money.of("0.1"),
                        Money.of("0.5"), Money.of("0.885"), T0 + 2 * HOUR));
    }

    private static List<SimulatedExecutor.FundingSettlement> funding() {
        return List.of(new SimulatedExecutor.FundingSettlement(BTC, T0 + 8 * HOUR,
                Money.of("0.0001"), Money.of("0.1"), Money.of("10050"), Money.of("-1.005")));
    }

    private static List<SimulatedExecutor.Rejection> rejections() {
        return List.of(new SimulatedExecutor.Rejection("co-rejected-3", BTC,
                "below minNotional after slippage", T0 + 3 * HOUR));
    }

    private static List<OrderRequestEvent> pending() {
        return List.of(OrderRequestEvent.of("co-pending-4", BTC, Side.BUY, OrderType.MARKET,
                Money.of("0.1"), null, T0 + 23 * HOUR));
    }

    private static List<Position> positions() {
        return List.of(new Position(BTC, Direction.LONG, Money.of("0.1"), Money.of("10005.5")));
    }
}
