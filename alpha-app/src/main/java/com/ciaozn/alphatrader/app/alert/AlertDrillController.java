package com.ciaozn.alphatrader.app.alert;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/alert/test} (T503): the manual half of the drill, so the operator can prove the
 * channel works at the moment they are about to go live rather than at startup hours earlier.
 *
 * <p>Online profiles only, like every other endpoint here: a backtest has no SMTP configuration and
 * no reason to have one.
 */
@RestController
@RequestMapping("/api/alert")
@Profile({"paper", "live"})
public class AlertDrillController {

    private final AlertDrill drill;

    public AlertDrillController(AlertDrill drill) {
        this.drill = drill;
    }

    @PostMapping("/test")
    public AlertDrill.Result test() {
        return drill.drill();
    }
}
