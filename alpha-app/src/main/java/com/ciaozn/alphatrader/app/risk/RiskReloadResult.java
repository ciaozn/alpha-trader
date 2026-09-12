package com.ciaozn.alphatrader.app.risk;

import java.util.List;

/**
 * The outcome of a hot-reload attempt (T404, FR-RK-09 / SEC-03): what the reload endpoint reports and,
 * on refusal, why the previous pipeline is still the one in force.
 *
 * <p>A value rather than an exception for the expected refusals - no confirmation, an invalid
 * configuration, a stopped engine. None of those is an error in the HTTP sense of "the server
 * malfunctioned"; each is an answer, and a client that has to catch an exception to learn "I forgot
 * confirm=true" would be reading control flow out of a stack trace. The controller maps
 * {@code reloaded == false} to 400 and the detail is written for whoever has the curl output.
 *
 * @param reloaded     true when the new pipeline was accepted and queued to the engine thread. It does
 *                     not mean the swap has already happened - it applies before the next signal, which
 *                     may be a bar away
 * @param detail       what happened, in the terms the caller needs: the confirmation rule, or the
 *                     validation failure that kept the old rules in force
 * @param signalRules  rule ids of the accepted pipeline's signal stage, in order; empty on refusal
 * @param orderRules   rule ids of the accepted pipeline's order stage, in order; empty on refusal
 */
public record RiskReloadResult(boolean reloaded, String detail,
                               List<String> signalRules, List<String> orderRules) {

    public RiskReloadResult {
        signalRules = List.copyOf(signalRules);
        orderRules = List.copyOf(orderRules);
    }

    static RiskReloadResult refused(String detail) {
        return new RiskReloadResult(false, detail, List.of(), List.of());
    }
}
