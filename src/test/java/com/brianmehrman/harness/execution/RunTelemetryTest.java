package com.brianmehrman.harness.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.workspace.SnapshotRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunTelemetryTest {
    @Test
    void attemptSpanHasIdsWhileCounterTagsStayBounded() {
        var meters = new SimpleMeterRegistry();
        var observations = ObservationRegistry.create();
        var observed = new AtomicReference<Observation.Context>();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStop(Observation.Context context) { observed.set(context); }
        });
        var telemetry = new RunTelemetry(meters, observations);
        var call = new ModelCall("run-sensitive-id", "invocation-sensitive-id", 1,
                "profile-sensitive-id", "blob", 1000);

        try (var attempt = telemetry.modelAttempt(call)) {
            attempt.succeeded();
        }

        assertThat(observed.get().getHighCardinalityKeyValue("run.id").getValue())
                .isEqualTo(call.runId());
        assertThat(observed.get().getHighCardinalityKeyValue("invocation.id").getValue())
                .isEqualTo(call.invocationId());
        var counter = meters.find("harness.attempts").counter();
        assertThat(counter.count()).isEqualTo(1);
        assertThat(counter.getId().getTags().toString())
                .doesNotContain(call.runId(), call.invocationId(), call.profileRevision());
    }

    @Test
    void failedObservationHandlerCannotChangeAttemptOutcome() {
        var meters = new SimpleMeterRegistry();
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStop(Observation.Context context) {
                throw new IllegalStateException("collector unavailable");
            }
        });
        var telemetry = new RunTelemetry(meters, observations);
        var call = new ModelCall("run-1", "turn-1", 1, "profile-1", "blob", 1000);

        assertThatCode(() -> {
            try (var attempt = telemetry.modelAttempt(call)) {
                attempt.succeeded();
            }
        }).doesNotThrowAnyException();
        assertThat(meters.find("harness.attempts").counter().count()).isEqualTo(1);
    }

    @Test
    void untrustedToolNamesAreBoundedAndCannotBreakTelemetry() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new RunTelemetry(meters, ObservationRegistry.create());
        var snapshot = new SnapshotRef("a".repeat(64));
        for (String name : new String[] {null, "secret-tool-name"}) {
            var call = new ToolInvocation("run-1", "turn-1", snapshot,
                    new ToolRequest("provider-1", name, "{}"), 1);
            assertThatCode(() -> {
                try (var attempt = telemetry.toolAttempt(call)) {
                    attempt.succeeded();
                }
            }).doesNotThrowAnyException();
        }
        var counter = meters.find("harness.attempts").tag("tool", "unknown").counter();
        assertThat(counter.count()).isEqualTo(2);
        assertThat(counter.getId().getTags().toString()).doesNotContain("secret-tool-name");
    }
}
