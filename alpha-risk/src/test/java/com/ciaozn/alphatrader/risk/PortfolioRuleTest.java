package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.RiskAlertEvent;
import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Direction;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-RK-04 / T305: the portfolio caps judged on the book <em>as it would be after this order fills</em>,
 * each from both sides of its boundary, the order they run in, and the reduction exemption that lets an
 * over-cap book trade down.
 *
 * <p>Equity 10000 and mark 100 put the total cap at {@code projectedTotal > 6000} and the symbol cap at
 * {@code projectedSymbol > 3000}. The helper takes this symbol's current signed qty and the rest of the
 * book's notional, and derives a consistent symbolNotional/totalNotional so {@code OrderFacts.of}'s
 * projection ({@code total - symbol + projectedSymbol}) is the realistic "others + this symbol after".
 */
class PortfolioRuleTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;
    private static final BigDecimal EQUITY = new BigDecimal("10000");
    private static final BigDecimal MARK = new BigDecimal("100");

    /** DESIGN §8 defaults: 60% total, 30% single symbol. */
    private final PortfolioRule rule = new PortfolioRule(new BigDecimal("0.60"), new BigDecimal("0.30"));

    /** @param otherNotional the rest of the book (every symbol except this one) at the mark */
    private static OrderFacts order(String signedQty, String otherNotional, Side side, String qty) {
        SignalEvent signal = SignalEvent.of("ma-cross-btc", BTC, Direction.LONG, 1.0, "test", T0);
        BigDecimal sq = new BigDecimal(signedQty);
        BigDecimal symbolNotional = Money.of(sq.abs().multiply(MARK));
        BigDecimal totalNotional = Money.of(symbolNotional.add(new BigDecimal(otherNotional)));
        SignalFacts facts = new SignalFacts(signal, EQUITY, EQUITY, totalNotional, symbolNotional, sq, MARK, T0);
        return OrderFacts.of(facts, side, new BigDecimal(qty), null);
    }

    // ------------------------------------------------------------------ within both caps

    @Test
    void anOrderThatKeepsTheBookWithinBothCapsPasses() {
        // projected symbol 1000, projected total 1000: both well inside.
        assertThat(rule.check(order("0", "0", Side.BUY, "10"))).isEmpty();
    }

    // ------------------------------------------------------------------ total cap, both sides

    @Test
    void projectedTotalExactlyAtTheCapPasses() {
        // others 5000 + this symbol 1000 = 6000 == cap: at the limit, not over it.
        assertThat(rule.check(order("0", "5000", Side.BUY, "10"))).isEmpty();
    }

    @Test
    void anOrderThatPushesTheProjectedTotalOverTheCapIsRefused() {
        // The current book (5000) is under the cap and the order's own notional (1001) is small, so only
        // the projection catches it - this is the "grows an oversized book one step at a time" case.
        Optional<RiskRejection> rejection = rule.check(order("0", "5000", Side.BUY, "10.01")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().ruleId()).isEqualTo(PortfolioRule.RULE_ID);
        assertThat(rejection.get().level()).isEqualTo(RiskRule.Level.PORTFOLIO);
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("portfolio total cap");
    }

    // ------------------------------------------------------------------ symbol cap, both sides

    @Test
    void projectedSymbolExactlyAtTheCapPasses() {
        // this symbol 30 x 100 = 3000 == cap, others 2000 keep the total under 6000.
        assertThat(rule.check(order("0", "2000", Side.BUY, "30"))).isEmpty();
    }

    @Test
    void anOrderThatPushesOneSymbolOverItsCapIsRefused() {
        // Accumulating an existing long 20 by 11 -> 31 (3100) breaches the 3000 symbol cap while the total
        // (3100) stays well under 6000, so this is the symbol cap firing on its own.
        Optional<RiskRejection> rejection = rule.check(order("20", "0", Side.BUY, "11")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().severity()).isEqualTo(RiskAlertEvent.Severity.WARNING);
        assertThat(rejection.get().detail()).contains("single-symbol cap");
    }

    // ------------------------------------------------------------------ the two caps' order

    @Test
    void anOrderBreachingBothReportsTheTotalCapFirst() {
        // projected symbol 4000 (> 3000) and projected total 8000 (> 6000): the broader account-wide limit
        // is reported, which only happens if the total cap is checked before the symbol cap.
        Optional<RiskRejection> rejection = rule.check(order("0", "4000", Side.BUY, "40")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().detail()).contains("portfolio total cap").doesNotContain("single-symbol");
    }

    // ------------------------------------------------------------------ reductions are exempt

    @Test
    void aReductionIsExemptEvenWhenTheBookStaysOverBothCaps() {
        // Long 50 (symbol 5000 > 3000) in a book of 7000 (> 6000): a partial close to 45 leaves the book at
        // 6500 and the symbol at 4500, both still over - but it is de-risking, so it must be allowed or the
        // account could never trade an over-concentrated book back down.
        assertThat(rule.check(order("50", "2000", Side.SELL, "5"))).isEmpty();
    }

    @Test
    void aFullCloseOfAnOverCapPositionIsExempt() {
        assertThat(rule.check(order("50", "2000", Side.SELL, "50"))).isEmpty();
    }

    @Test
    void aShortPositionReducingIsExemptTheSameWay() {
        // The exemption is on magnitude, not side: covering part of an over-cap short is de-risking too.
        assertThat(rule.check(order("-50", "2000", Side.BUY, "5"))).isEmpty();
    }

    // ------------------------------------------------------------------ a flip is not a reduction

    @Test
    void aFlipToALargerMagnitudeIsNotAReductionAndIsCapped() {
        // Long 10, sell 45 -> short 35: |35| > |10|, so it takes on new exposure; projected symbol 3500
        // breaches the 3000 cap and is refused rather than treated as a close.
        Optional<RiskRejection> rejection = rule.check(order("10", "0", Side.SELL, "45")).stream().findFirst();
        assertThat(rejection).isPresent();
        assertThat(rejection.get().detail()).contains("single-symbol cap");
    }

    // ------------------------------------------------------------------ identity and construction guards

    @Test
    void exposesItsIdAndLevel() {
        assertThat(rule.ruleId()).isEqualTo("RK-04-portfolio");
        assertThat(rule.level()).isEqualTo(RiskRule.Level.PORTFOLIO);
    }

    @Test
    void refusesANonPositiveTotalCap() {
        assertThatThrownBy(() -> new PortfolioRule(BigDecimal.ZERO, new BigDecimal("0.30")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTotalFraction");
        assertThatThrownBy(() -> new PortfolioRule(null, new BigDecimal("0.30")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTotalFraction");
    }

    @Test
    void refusesANonPositiveSymbolCap() {
        assertThatThrownBy(() -> new PortfolioRule(new BigDecimal("0.60"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSymbolFraction");
        assertThatThrownBy(() -> new PortfolioRule(new BigDecimal("0.60"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSymbolFraction");
    }

    @Test
    void refusesASymbolCapAboveTheTotalCapButAcceptsEquality() {
        // One symbol's notional is part of the total, so a symbol cap above the total cap is incoherent.
        assertThatThrownBy(() -> new PortfolioRule(new BigDecimal("0.30"), new BigDecimal("0.60")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
        // Equal caps are coherent (a single-symbol book): the boundary is inclusive.
        assertThat(new PortfolioRule(new BigDecimal("0.60"), new BigDecimal("0.60")).ruleId())
                .isEqualTo(PortfolioRule.RULE_ID);
    }
}
