package com.ciaozn.alphatrader.app.risk;

import com.ciaozn.alphatrader.app.config.AlphaProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/risk/reload?confirm=true} (T404, FR-RK-09 / SEC-03): the operator-facing way to
 * change the account's risk limits without a restart.
 *
 * <p><b>Online profiles only, and the confirmation is a required parameter rather than a body field.</b>
 * Putting {@code confirm} in the query string means a caller cannot set it by accident while filling in
 * a JSON body, and a request that omits it is refused before the pipeline is even built. The service is
 * what enforces it; this class only carries the flag, so the rule holds no matter how the endpoint is
 * reached.
 *
 * <p><b>Why a REST endpoint and not a Spring actuator or a file poll.</b> An actuator endpoint would
 * expose the trading account's limits alongside generic health, and a config-file watcher would make an
 * edit on disk a live change with no confirmation step at all. A dedicated POST with an explicit flag
 * is the shape SEC-03 asks for: a deliberate act, with a yes.
 *
 * <p>A refusal answers 400 with the same {@link RiskReloadResult} shape a success returns, so a client
 * parses one document either way. An unparseable body is refused by Spring before this method runs -
 * still 400, still no change to the live pipeline.
 */
@RestController
@RequestMapping("/api")
@Profile({"paper", "live"})
public class RiskReloadController {

    private final RiskReloadService service;

    public RiskReloadController(RiskReloadService service) {
        this.service = service;
    }

    @PostMapping("/risk/reload")
    public ResponseEntity<RiskReloadResult> reload(
            @RequestParam(name = "confirm", defaultValue = "false") boolean confirm,
            @RequestBody AlphaProperties.Risk risk) {
        RiskReloadResult result = service.reload(risk, confirm);
        return result.reloaded()
                ? ResponseEntity.ok(result)
                : ResponseEntity.badRequest().body(result);
    }
}
