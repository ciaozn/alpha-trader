package com.ciaozn.alphatrader.app.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Drills the alert channel once at startup when configured to (T503, P5-1).
 *
 * <p>Off by default and switched on in the live profile: a drill is a real email, and a process that
 * restarts a few times while being configured would send a few of them. Live is where an undetected
 * broken channel actually costs something.
 *
 * <p>Order 4: after the preflight checklist, which reports whether the channel is configured at all -
 * "configured" and "delivers" are different claims, and this is the second one.
 */
@Component
@Profile({"paper", "live"})
@Order(4)
public class AlertDrillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertDrillRunner.class);

    private final AlertDrill drill;
    private final boolean drillOnStartup;

    public AlertDrillRunner(AlertDrill drill,
                            @Value("${alpha.alert.drill-on-startup:false}") boolean drillOnStartup) {
        this.drill = drill;
        this.drillOnStartup = drillOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!drillOnStartup) {
            return;
        }
        AlertDrill.Result result = drill.drill();
        if (result.ok()) {
            log.info("Startup alert drill: {} - {}", result.outcome(), result.detail());
        } else {
            log.error("Startup alert drill FAILED: {} - {}. Alerts will not reach the operator.",
                    result.outcome(), result.detail());
        }
    }
}
