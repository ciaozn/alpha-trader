package com.ciaozn.alphatrader.app.alert;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AlertDrillTest {

    private record Sent(String subject, String body) {
    }

    private static AlertProperties properties(boolean enabled) {
        return new AlertProperties(enabled, "ciaozn@qq.com", "ciaozn@qq.com", "ciaozn@qq.com",
                null, null, null, null);
    }

    @Test
    void aDisabledChannelReportsSkippedRatherThanPretendingToWork() {
        List<Sent> sent = new ArrayList<>();
        AlertDrill drill = new AlertDrill(properties(false), (subject, body) -> sent.add(new Sent(subject, body)));

        AlertDrill.Result result = drill.drill();

        assertThat(result.outcome()).isEqualTo(AlertDrill.Outcome.SKIPPED);
        assertThat(result.ok()).isTrue();
        assertThat(sent).isEmpty();
    }

    @Test
    void anEnabledChannelActuallySends() {
        List<Sent> sent = new ArrayList<>();
        AlertDrill drill = new AlertDrill(properties(true), (subject, body) -> sent.add(new Sent(subject, body)));

        AlertDrill.Result result = drill.drill();

        assertThat(result.outcome()).isEqualTo(AlertDrill.Outcome.SENT);
        assertThat(result.detail()).contains("ciaozn@qq.com");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).subject()).contains("drill");
        assertThat(sent.get(0).body()).contains("No action is needed");
    }

    @Test
    void aFailedDrillReportsTheReasonInsteadOfThrowing() {
        // The reason is the whole point: "address rejected" and "connection timed out" share only the
        // word failure, and the operator has to act on the difference.
        AlertDrill drill = new AlertDrill(properties(true), (subject, body) -> {
            throw new IllegalStateException("535 authentication failed");
        });

        AlertDrill.Result result = drill.drill();

        assertThat(result.outcome()).isEqualTo(AlertDrill.Outcome.FAILED);
        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("535 authentication failed");
    }
}
