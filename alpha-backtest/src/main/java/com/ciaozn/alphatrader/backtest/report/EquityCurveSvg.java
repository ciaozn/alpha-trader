package com.ciaozn.alphatrader.backtest.report;

import com.ciaozn.alphatrader.common.model.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Renders the equity curve as inline SVG (FR-BT-04). Inline rather than a PNG or a chart library
 * because the report has to be one self-contained file with no external CDN dependency: a
 * {@code <script src>} to a charting library would make the report stop working offline, and would
 * quietly change what it displays whenever that library was upgraded.
 *
 * <p>Deterministic by construction: fixed pixel geometry, integer coordinates, downsampling by a
 * stride computed from the point count. Nothing here reads a clock, a locale or a screen size, so
 * the same curve yields byte-identical markup (SC-02).
 */
final class EquityCurveSvg {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 300;
    private static final int PAD_LEFT = 78;
    private static final int PAD_RIGHT = 18;
    private static final int PAD_TOP = 16;
    private static final int PAD_BOTTOM = 34;
    private static final int PLOT_W = WIDTH - PAD_LEFT - PAD_RIGHT;
    private static final int PLOT_H = HEIGHT - PAD_TOP - PAD_BOTTOM;
    private static final BigDecimal FOUR = BigDecimal.valueOf(4);
    private static final BigDecimal HEADROOM = new BigDecimal("0.06");

    /**
     * Most vertices drawn. A three-year hourly run samples ~26k points, and a polyline that long is
     * both a multi-hundred-kilobyte file and, at this width, indistinguishable from one a quarter
     * its size. The stride keeps every {@code MAX_POINTS}-th sample and always the last one, so the
     * final equity - the point the whole curve is read against - is never stepped over.
     */
    private static final int MAX_POINTS = 2000;

    private EquityCurveSvg() {
    }

    /** Vertical window the curve is drawn into, already padded. */
    private record Scale(BigDecimal low, BigDecimal span) {

        private int yOf(BigDecimal equity) {
            double fraction = equity.subtract(low).doubleValue() / span.doubleValue();
            return PAD_TOP + PLOT_H - (int) Math.round(fraction * PLOT_H);
        }
    }

    static String render(EquityCurve curve, Optional<PerformanceMetrics.Drawdown> worst) {
        List<EquityCurve.Point> points = curve.points();
        BigDecimal starting = curve.startingEquity();
        Scale scale = scale(points, starting);

        long firstTs = points.isEmpty() ? 0 : points.get(0).businessTs();
        long lastTs = points.isEmpty() ? 1 : points.get(points.size() - 1).businessTs();
        // A single sample has no width to span, and dividing by zero would put every vertex at the
        // left edge; one pixel of span keeps it a dot in the middle instead.
        long xSpan = Math.max(1L, lastTs - firstTs);

        StringBuilder svg = new StringBuilder(8192);
        svg.append("<svg class=\"curve\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
                .append("\" role=\"img\" aria-label=\"净值曲线\">");
        appendGrid(svg, scale, firstTs, lastTs);
        appendBaseline(svg, starting, scale);
        if (worst.isPresent()) {
            appendDrawdown(svg, worst.get(), scale, firstTs, xSpan);
        }
        appendCurve(svg, points, scale, firstTs, xSpan);
        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * The vertical window: every sample plus the starting equity, with headroom so the line never
     * touches the frame. A perfectly flat run gets a synthetic window around the starting equity -
     * its span would otherwise be zero and every {@code yOf} would divide by it.
     */
    private static Scale scale(List<EquityCurve.Point> points, BigDecimal starting) {
        BigDecimal low = starting;
        BigDecimal high = starting;
        for (EquityCurve.Point point : points) {
            if (point.equity().compareTo(low) < 0) {
                low = point.equity();
            }
            if (point.equity().compareTo(high) > 0) {
                high = point.equity();
            }
        }
        if (high.compareTo(low) == 0) {
            BigDecimal around = starting.signum() == 0 ? BigDecimal.ONE : starting.abs();
            low = starting.subtract(around);
            high = starting.add(around);
        }
        BigDecimal pad = high.subtract(low).multiply(HEADROOM, Money.MC);
        low = low.subtract(pad);
        high = high.add(pad);
        return new Scale(low, high.subtract(low));
    }

    private static void appendGrid(StringBuilder svg, Scale scale, long firstTs, long lastTs) {
        svg.append("<rect class=\"plot\" x=\"").append(PAD_LEFT).append("\" y=\"").append(PAD_TOP)
                .append("\" width=\"").append(PLOT_W).append("\" height=\"").append(PLOT_H).append("\"/>");
        int labelScale = labelScale(scale.span());
        for (int i = 0; i <= 4; i++) {
            BigDecimal value = scale.low().add(scale.span()
                    .multiply(BigDecimal.valueOf(i), Money.MC)
                    .divide(FOUR, Money.MC));
            int y = PAD_TOP + PLOT_H - Math.round(PLOT_H * i / 4.0f);
            svg.append("<line class=\"grid\" x1=\"").append(PAD_LEFT).append("\" y1=\"").append(y)
                    .append("\" x2=\"").append(PAD_LEFT + PLOT_W).append("\" y2=\"").append(y).append("\"/>");
            svg.append("<text class=\"ylabel\" x=\"").append(PAD_LEFT - 8).append("\" y=\"").append(y + 4)
                    .append("\" text-anchor=\"end\">")
                    .append(ReportFormat.fixed(value.doubleValue(), labelScale)).append("</text>");
        }
        svg.append("<text class=\"xlabel\" x=\"").append(PAD_LEFT).append("\" y=\"").append(HEIGHT - 10)
                .append("\">").append(ReportFormat.time(firstTs)).append("</text>");
        svg.append("<text class=\"xlabel\" x=\"").append(PAD_LEFT + PLOT_W).append("\" y=\"")
                .append(HEIGHT - 10).append("\" text-anchor=\"end\">")
                .append(ReportFormat.time(lastTs)).append("</text>");
    }

    /**
     * Decimals for the axis labels, chosen so five of them never collapse onto the same integer: a
     * small account or a short quiet run spans less than a unit and would otherwise print an axis
     * that says nothing. Rounding is legitimate here and only here - these are visual guides, not
     * reported values, which is why this does not contradict {@link ReportFormat}'s never-round rule.
     */
    private static int labelScale(BigDecimal span) {
        double magnitude = span.doubleValue();
        if (magnitude < 1) {
            return 6;
        }
        if (magnitude < 10) {
            return 4;
        }
        if (magnitude < 1000) {
            return 2;
        }
        return 0;
    }

    private static void appendBaseline(StringBuilder svg, BigDecimal starting, Scale scale) {
        int y = scale.yOf(starting);
        svg.append("<line class=\"baseline\" x1=\"").append(PAD_LEFT).append("\" y1=\"").append(y)
                .append("\" x2=\"").append(PAD_LEFT + PLOT_W).append("\" y2=\"").append(y).append("\"/>");
        svg.append("<text class=\"baselinelabel\" x=\"").append(PAD_LEFT + 4).append("\" y=\"")
                .append(y - 4).append("\">初始权益 ").append(ReportFormat.amount(starting))
                .append("</text>");
    }

    /** The worst drawdown as two vertical markers, so the depth in the table can be seen in place. */
    private static void appendDrawdown(StringBuilder svg, PerformanceMetrics.Drawdown worst,
                                       Scale scale, long firstTs, long xSpan) {
        int peakX = xOf(worst.peakTs(), firstTs, xSpan);
        int troughX = xOf(worst.troughTs(), firstTs, xSpan);
        int bottom = PAD_TOP + PLOT_H;
        svg.append("<line class=\"drawdown\" x1=\"").append(peakX).append("\" y1=\"").append(PAD_TOP)
                .append("\" x2=\"").append(peakX).append("\" y2=\"").append(bottom).append("\"/>");
        svg.append("<line class=\"drawdown\" x1=\"").append(troughX).append("\" y1=\"").append(PAD_TOP)
                .append("\" x2=\"").append(troughX).append("\" y2=\"").append(bottom).append("\"/>");
        svg.append("<text class=\"drawdownlabel\" x=\"").append(Math.min(peakX, troughX) + 4)
                .append("\" y=\"").append(PAD_TOP + 12).append("\">最大回撤 ")
                .append(ReportFormat.percent(worst.depth())).append("</text>");
    }

    private static void appendCurve(StringBuilder svg, List<EquityCurve.Point> points, Scale scale,
                                    long firstTs, long xSpan) {
        if (points.isEmpty()) {
            return;
        }
        if (points.size() == 1) {
            // A polyline of one vertex renders nothing at all, which would read as "no data".
            EquityCurve.Point only = points.get(0);
            svg.append("<circle class=\"dot\" cx=\"").append(xOf(only.businessTs(), firstTs, xSpan))
                    .append("\" cy=\"").append(scale.yOf(only.equity())).append("\" r=\"2.5\"/>");
            return;
        }
        int stride = Math.max(1, (int) Math.ceil(points.size() / (double) MAX_POINTS));
        svg.append("<polyline class=\"equity\" points=\"");
        for (int i = 0; i < points.size(); i += stride) {
            appendVertex(svg, points.get(i), scale, firstTs, xSpan);
        }
        if ((points.size() - 1) % stride != 0) {
            appendVertex(svg, points.get(points.size() - 1), scale, firstTs, xSpan);
        }
        svg.append("\"/>");
    }

    private static void appendVertex(StringBuilder svg, EquityCurve.Point point, Scale scale,
                                     long firstTs, long xSpan) {
        svg.append(xOf(point.businessTs(), firstTs, xSpan)).append(',')
                .append(scale.yOf(point.equity())).append(' ');
    }

    private static int xOf(long businessTs, long firstTs, long xSpan) {
        return PAD_LEFT + (int) Math.round((businessTs - firstTs) / (double) xSpan * PLOT_W);
    }
}
