package com.ciaozn.alphatrader.app.status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/status} (T402, FR-OP-02): one request's worth of "is everything alright, and what is it
 * doing".
 *
 * <p><b>Online profiles only, and that is the load-bearing part of this task.</b> {@code backtest} and
 * {@code download} are jobs: they set {@code spring.main.web-application-type: none} and are expected to
 * run their work and exit. The controller is on its own thin and would cost those profiles nothing to
 * build - but everything it reads belongs to a running engine, and the point of the profile mechanism in
 * this project (see {@code OnlineWiringConfig}) is that a profile either has the whole online assembly or
 * none of it. An unannotated bean here would be the first online component those profiles ever saw.
 *
 * <p><b>Always 200, even when degraded.</b> A status endpoint that answers 503 when the engine is down is
 * indistinguishable over a flaky network from one answering 503 because the reverse proxy lost it, and it
 * trains whoever writes the alerting threshold to treat "no answer" and "bad answer" as one case. The
 * distinction is carried inside the document instead: {@code healthy} is what a monitor branches on, and
 * {@code note} says what could not be read.
 *
 * <p><b>This class does no formatting and reads no state.</b> Everything arrives assembled by
 * {@link StatusSnapshotFactory} and goes out as Jackson writes it - a controller that mapped fields by
 * hand would have to be edited every time somebody adds one, which is how two definitions of "the status"
 * drift apart.
 */
@RestController
@RequestMapping("/api")
@Profile({"paper", "live"})
public class StatusController {

    private static final Logger log = LoggerFactory.getLogger(StatusController.class);

    private final StatusSnapshotFactory factory;

    public StatusController(StatusSnapshotFactory factory) {
        this.factory = factory;
    }

    @GetMapping("/status")
    public StatusSnapshot status() {
        StatusSnapshot snapshot = factory.snapshot();
        if (!snapshot.healthy()) {
            log.warn("Status served as degraded: {}", snapshot.note());
        }
        return snapshot;
    }
}
