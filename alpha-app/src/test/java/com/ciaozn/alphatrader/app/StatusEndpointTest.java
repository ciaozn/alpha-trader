package com.ciaozn.alphatrader.app;

import com.ciaozn.alphatrader.app.status.GatewayHealthProbe;
import com.ciaozn.alphatrader.app.status.StatusController;
import com.ciaozn.alphatrader.common.event.OrderRequestEvent;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Interval;
import com.ciaozn.alphatrader.common.model.Side;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.model.TradingRulesProvider;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.gateway.AccountSnapshot;
import com.ciaozn.alphatrader.gateway.ExchangeGateway;
import com.ciaozn.alphatrader.gateway.GatewayConfig;
import com.ciaozn.alphatrader.gateway.OpenOrder;
import com.ciaozn.alphatrader.gateway.OrderAck;
import com.ciaozn.alphatrader.gateway.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/status} out of a real container (T402's acceptance criterion): every field FR-OP-02 asks
 * for is present in the document, whatever the values happen to be at that instant.
 *
 * <p>Asserting on the shape rather than on the numbers is deliberate. Values here depend on how many bars
 * arrived and when the probe last ran, so pinning them would make this a flake generator; what has to hold
 * is that a monitoring script can read every field it was written against, which is exactly what breaks
 * when somebody renames one with nothing failing. The numbers themselves are asserted where they are made -
 * {@code StatusSnapshotFactoryTest} and the components it reads.
 *
 * <p>Two things are driven first, though, so that no assertion is made against a null: one event published
 * into the loop, and one probe run. Both are waited for rather than slept for, because "wait until the
 * observable answer changes" is what distinguishes a slow start from a broken one.
 *
 * <p>The answer is 200 even though this container can never be truly healthy - no gateway is contactable -
 * which is the point: an endpoint answering 503 for a degraded system would teach every monitor to read
 * "the app refused the connection" and "trading is broken" as one event. The distinction lives in the
 * document, in {@code healthy} and {@code note}.
 */
@SpringBootTest(properties = {
        "alpha.mode=paper",
        "alpha.trading.enabled=false",
        "alpha.symbols[0]=BTCUSDT.PERP",
        "alpha.store.jdbc-url=jdbc:sqlite:target/status-endpoint-test.db"
})
@ActiveProfiles("paper")
@AutoConfigureMockMvc
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class StatusEndpointTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Portfolio portfolio;

    @Autowired
    private EventEngine engine;

    @Autowired
    private GatewayHealthProbe probe;

    @BeforeEach
    void makeSomethingToReport() throws InterruptedException {
        // Once, not once per method: the portfolio is a singleton in this context and @BeforeEach runs
        // per test, so applying another fill each time would make the assertions below depend on how
        // many tests happened to run before this one.
        if (portfolio.position(BTC).isEmpty()) {
            portfolio.applyFill(BTC, Side.BUY, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ZERO);
        }
        portfolio.mark(BTC, new BigDecimal("110"));
        engine.publish(TimerEvent.of("status-test", System.currentTimeMillis()));
        // The wiring started the probe on its 60s schedule; re-arm it short enough that this test does not
        // wait a minute for its first answer. Closing first is legitimate here because the probe is this
        // context's own bean and nothing else holds it.
        probe.close();
        probe.start(Duration.ofMillis(20));
        await(text -> text.contains("\"state\":\"CONNECTED\""), "the probe answered");
        await(text -> !text.contains("\"lastEventMillis\":null"), "the loop observed the event");
    }

    @Test
    void everyFieldTheSpecAsksForIsInTheDocument() throws Exception {
        String body = served();

        assertThat(body)
                .contains("\"mode\":\"paper\"")
                .contains("\"healthy\"")
                .contains("\"generatedAtMillis\"")
                // 引擎心跳
                .contains("\"engine\":{\"alive\":true")
                .contains("\"eventsObserved\"")
                .contains("\"lastEventMillis\"")
                .contains("\"sinceLastEventMillis\"")
                // 网关连接状态与延迟
                .contains("\"state\":\"CONNECTED\"")
                .contains("\"connected\":true")
                .contains("\"latencyMillis\"")
                .contains("\"sinceLastProbeMillis\"")
                // 账户权益与今日已实现盈亏
                .contains("\"equity\"")
                .contains("\"cash\"")
                .contains("\"unrealizedPnl\"")
                .contains("\"realizedPnlToday\"")
                .contains("\"positions\":[");
    }

    @Test
    void theAccountAndThePositionThisTestCreatedAreInTheAnswer() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.equity").value(10010.0))
                .andExpect(jsonPath("$.account.unrealizedPnl").value(10.0))
                .andExpect(jsonPath("$.account.realizedPnlToday").value(0.0))
                .andExpect(jsonPath("$.positions[0].symbol").value("BTCUSDT.PERP"))
                .andExpect(jsonPath("$.positions[0].direction").value("LONG"))
                .andExpect(jsonPath("$.positions[0].qty").value(1.0))
                .andExpect(jsonPath("$.positions[0].markPrice").value(110.0));
    }

    @Test
    void theLoopAndTheProbeAreBothLiveInThisContainer() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine.alive").value(true))
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.note").isEmpty())
                .andExpect(jsonPath("$.gateway.state").value("CONNECTED"));
    }

    @Test
    void theControllerExistsOnlyBecauseThisProfileIsOnline() {
        // Stated rather than assumed: if this ever stopped being true the 200s above would be the only
        // other witness - and they would still be served, because a missing mapping answers 404, not red.
        assertThat(context.getBeanNamesForType(StatusController.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(GatewayHealthProbe.class)).isNotEmpty();
    }

    private String served() throws Exception {
        return mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void await(java.util.function.Predicate<String> condition, String what)
            throws InterruptedException {
        String body = "";
        for (int i = 0; i < 100; i++) {
            try {
                body = mvc.perform(get("/api/status")).andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException("could not read /api/status", e);
            }
            if (condition.test(body)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what + "; last document was " + body);
    }

    @TestConfiguration
    static class Stubs {

        /** A gateway that answers locally, so the container never touches the network. */
        @Bean
        @Primary
        ExchangeGateway fakeGateway() {
            return new ExchangeGateway() {
                @Override
                public void connect(GatewayConfig config) {
                }

                @Override
                public void subscribeKline(Symbol symbol, Interval interval) {
                }

                @Override
                public OrderAck placeOrder(OrderRequestEvent request) {
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
            };
        }

        /** Keeps the container off the network; the real provider fetches rules from exchangeInfo. */
        @Bean
        @Primary
        TradingRulesProvider fixedRules() {
            return FixedTradingRulesProvider.of(new TradingRules(BTC,
                    new BigDecimal("0.10"), new BigDecimal("0.001"), new BigDecimal("5")));
        }
    }
}
