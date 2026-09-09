package com.ciaozn.alphatrader.risk;

import com.ciaozn.alphatrader.common.event.SignalEvent;
import com.ciaozn.alphatrader.common.model.Money;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.portfolio.Position;

import java.math.BigDecimal;

/**
 * Everything a rule may know about a signal <em>before</em> FR-RK-07 has sized it: what the
 * strategy asked for, and the account as the book shows it at that instant.
 *
 * <p>This is also the account snapshot the interception record stores (FR-RK-08, "包含命中的规则与
 * 当时账户状态"): it is captured once, before any rule runs, so a rule that rejects and a rule that
 * passes see the same numbers, and the record cannot drift from the decision it explains.
 *
 * <p>{@code price} is the book's mark for the symbol, which falls back to the entry price and then
 * to zero - so it is never null but it can be unusable, and saying so is FR-RK-07's job
 * ({@code PositionSizer.RULE_NO_PRICE}), not a rule's.
 */
public record SignalFacts(
        SignalEvent signal,
        BigDecimal equity,
        BigDecimal cash,
        BigDecimal totalNotional,
        BigDecimal symbolNotional,
        BigDecimal signedQty,
        BigDecimal price,
        long nowMillis) {

    public static SignalFacts of(SignalEvent signal, Portfolio portfolio, long nowMillis) {
        Position position = portfolio.position(signal.symbol());
        BigDecimal price = portfolio.markOf(signal.symbol());
        return new SignalFacts(signal, portfolio.equity(), portfolio.cash(), portfolio.totalNotional(),
                Money.of(position.qty().multiply(price)), position.signedQty(), price, nowMillis);
    }

    public Symbol symbol() {
        return signal.symbol();
    }
}
