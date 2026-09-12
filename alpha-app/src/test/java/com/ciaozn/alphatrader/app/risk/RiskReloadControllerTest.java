package com.ciaozn.alphatrader.app.risk;

import com.ciaozn.alphatrader.common.event.Event;
import com.ciaozn.alphatrader.common.event.TimerEvent;
import com.ciaozn.alphatrader.common.model.FixedTradingRulesProvider;
import com.ciaozn.alphatrader.common.model.Symbol;
import com.ciaozn.alphatrader.common.model.TradingRules;
import com.ciaozn.alphatrader.common.portfolio.Portfolio;
import com.ciaozn.alphatrader.common.time.VirtualClock;
import com.ciaozn.alphatrader.engine.EventEngine;
import com.ciaozn.alphatrader.engine.EventJournal;
import com.ciaozn.alphatrader.risk.InMemoryRecordStore;
import com.ciaozn.alphatrader.risk.PositionSizer;
import com.ciaozn.alphatrader.risk.RiskGate;
import com.ciaozn.alphatrader.risk.RiskPipeline;
import com.ciaozn.alphatrader.risk.RiskRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T404's interface: the HTTP shape of {@code POST /api/risk/reload} and, more importantly, the two
 * things it must refuse. A reload endpoint is the one place where a malformed request could change the
 * account's limits, so "400 and nothing moved" is asserted directly against the live gate rather than
 * inferred from the status code.
 *
 * <p>Standalone MockMvc: the controller is exercised with the real service, gate and a running engine,
 * which is enough to prove the wiring and the refusal paths without paying for a Spring context. The
 * profile guard is asserted reflectively, because a standalone setup deliberately ignores profiles.
 */
class RiskReloadControllerTest {

    private static final Symbol BTC = Symbol.parse("BTCUSDT.PERP");
    private static final long T0 = 1_700_000_000_000L;

    private static final String TIGHTER = "{\"portfolio\":{\"max-total-notional-fraction\":0.05,"
            + "\"max-symbol-notional-fraction\":0.05}}";
    /** symbol cap above total cap: the record refuses it before any rule is built. */
    private static final String INVALID = "{\"portfolio\":{\"max-total-notional-fraction\":0.30,"
            + "\"max-symbol-notional-fraction\":0.60}}";

    private final Portfolio portfolio = new Portfolio(new BigDecimal("10000"));
    private final VirtualClock clock = new VirtualClock(T0);
    private final List<Event> published = new CopyOnWriteArrayList<>();

    private EventEngine engine;
    private RiskGate gate;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        portfolio.mark(BTC, new BigDecimal("100"));
        engine = new EventEngine(EventJournal.noop(), clock);
        gate = new RiskGate(portfolio, new PositionSizer(PositionSizer.Policy.DEFAULT),
                FixedTradingRulesProvider.of(new TradingRules(BTC, new BigDecimal("0.10"),
                        new BigDecimal("0.001"), new BigDecimal("5"))),
                RiskPipeline.empty(), clock, new InMemoryRecordStore());
        engine.registerHandler(gate);
        engine.registerHandler((event, publisher) -> published.add(event));
        engine.start();
        RiskReloadService service = new RiskReloadService(gate, portfolio, engine, clock);
        mvc = MockMvcBuilders.standaloneSetup(new RiskReloadController(service)).build();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.stop();
        }
    }

    /** Drives the loop to quiescence so the swap the service queued has certainly been applied. */
    private void drainLoop() throws InterruptedException {
        TimerEvent probe = TimerEvent.of("reload-probe", clock.nowMillis());
        engine.publish(probe);
        engine.awaitQuiescence(probe.eventId());
    }

    @Test
    void aRequestWithoutConfirmIsRefusedWith400AndTheLivePipelineIsUnchanged() throws Exception {
        RiskPipeline before = gate.pipeline();

        mvc.perform(post("/api/risk/reload").contentType(MediaType.APPLICATION_JSON).content(TIGHTER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reloaded").value(false));

        drainLoop();
        assertThat(gate.pipeline()).isSameAs(before);
    }

    @Test
    void anInvalidBodyIsRefusedWith400AndTheLivePipelineIsUnchanged() throws Exception {
        RiskPipeline before = gate.pipeline();

        mvc.perform(post("/api/risk/reload").param("confirm", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(INVALID))
                .andExpect(status().isBadRequest());

        drainLoop();
        assertThat(gate.pipeline()).isSameAs(before);
    }

    @Test
    void aConfirmedValidRequestIsAcceptedAndAppliedWithoutARestart() throws Exception {
        mvc.perform(post("/api/risk/reload").param("confirm", "true")
                        .contentType(MediaType.APPLICATION_JSON).content(TIGHTER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reloaded").value(true));

        drainLoop();
        assertThat(gate.pipeline().orderRules()).extracting(RiskRule::ruleId)
                .containsExactly("RK-03-order", "RK-04-portfolio");
    }

    @Test
    void theEndpointExistsOnlyInTheOnlineProfiles() {
        Profile profile = RiskReloadController.class.getAnnotation(Profile.class);
        assertThat(profile).as("@Profile on the reload endpoint").isNotNull();
        assertThat(Arrays.asList(profile.value())).containsExactlyInAnyOrder("paper", "live");
    }
}
