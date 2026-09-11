package com.ciaozn.alphatrader.app;

import com.ciaozn.alphatrader.app.config.OnlineHandlers;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The paper profile boots a complete online assembly with no exchange attached (T319).
 *
 * <p>What this test is really for: the container once started with {@code StartupWiring} registering
 * only the strategy engine - every other handler existed and had tests, and none of them was
 * connected. A green build said nothing about the online path. Asserting on the assembled sequence
 * here is what makes "the gate is in the loop" a CI-checked fact instead of a code-review
 * convention.
 */
@SpringBootTest(properties = {
        "alpha.mode=paper",
        "alpha.trading.enabled=false",
        "alpha.symbols[0]=BTCUSDT.PERP",
        "alpha.store.jdbc-url=jdbc:sqlite:target/paper-profile-test.db"
})
@ActiveProfiles("paper")
// The engine's loop thread is deliberately non-daemon - it is what keeps a non-web JVM alive
// between bars - so a cached context would leave that thread running for the rest of the JVM and
// break the backtest profile's "no thread survives to keep the process alive" guarantee. Closing
// this context after the class is the honest fix: it also stops the sender and the reconciliation
// thread the same way a real shutdown would.
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class PaperProfileApplicationTest {

    @Autowired
    OnlineHandlers handlers;

    @Autowired
    FakeGateway gateway;

    @Test
    void theWholeOnlineAssemblyIsWired() {
        // Six named components plus every rule that is itself a handler - the circuit breaker among
        // them, which has to observe fills or it can never count a losing streak.
        assertThat(handlers.names()).contains("MarkPriceUpdater", "SignalRecorder", "RiskGate",
                "OrderManager", "SnapshotSampler", "StrategyEngine", "RK-05-breaker");
        assertThat(handlers.handlers()).hasSameSizeAs(handlers.names());
        assertThat(handlers.indexOf("RK-05-breaker")).isGreaterThan(handlers.indexOf("RiskGate"));
    }

    @Test
    void dispatchOrderPutsTheRecorderAheadOfTheGate() {
        // The interception row the gate writes joins to the signal row on event id: if the gate ran
        // first, a crash between the two writes would leave an interception naming a signal that was
        // never recorded.
        assertThat(handlers.indexOf("SignalRecorder")).isLessThan(handlers.indexOf("RiskGate"));
    }

    @Test
    void marksComeFirstAndStrategiesLast() {
        // Rules read equity, and equity needs a price; strategies must not see this bar's fills
        // before producing this bar's signals (FR-BT-02's discipline, kept in the online path).
        assertThat(handlers.indexOf("MarkPriceUpdater")).isZero();
        assertThat(handlers.indexOf("StrategyEngine")).isEqualTo(handlers.names().size() - 1);
    }

    @Test
    void nothingIsSentToAnExchangeOnStartup() {
        assertThat(gateway.placed).isEmpty();
        assertThat(gateway.connected).isTrue();
    }

    /** Stands in for the real gateway: records what the assembly asks of it, answers nothing risky. */
    static final class FakeGateway implements ExchangeGateway {
        final List<OrderRequestEvent> placed = new ArrayList<>();
        boolean connected;

        @Override
        public void connect(GatewayConfig config) {
            connected = true;
        }

        @Override
        public void subscribeKline(Symbol symbol, Interval interval) {
        }

        @Override
        public OrderAck placeOrder(OrderRequestEvent request) {
            placed.add(request);
            return OrderAck.accepted(request.clientOrderId(), "fake-1");
        }

        @Override
        public void cancelOrder(String clientOrderId) {
        }

        @Override
        public AccountSnapshot queryAccount() {
            return new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        @Override
        public List<Position> queryPositions() {
            return List.of();
        }

        @Override
        public List<OpenOrder> queryOpenOrders() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    @TestConfiguration
    static class Stubs {

        @Bean
        @Primary
        ExchangeGateway fakeGateway() {
            return new FakeGateway();
        }

        /** Keeps the container test off the network; the real provider fetches from the exchange. */
        @Bean
        @Primary
        TradingRulesProvider fixedRules() {
            return FixedTradingRulesProvider.of(new TradingRules(Symbol.parse("BTCUSDT.PERP"),
                    new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("5")));
        }
    }
}
