package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelCall;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Bounded metrics and correlated observations; diagnostics cannot control run execution. */
@Component
public class RunTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(RunTelemetry.class);
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public RunTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = Objects.requireNonNull(meters);
        this.observations = Objects.requireNonNull(observations);
    }

    public Attempt modelAttempt(ModelCall call) {
        return begin("model", "none", call.runId(), call.invocationId());
    }

    public Attempt toolAttempt(ToolInvocation call) {
        String requestedName = call.request().name();
        String name = requestedName == null ? "unknown" : switch (requestedName) {
            case "read_file", "list_files", "write_file", "run_tests", "build", "submit" ->
                    call.request().name();
            default -> "unknown";
        };
        return begin("tool", name, call.runId(), call.invocationId());
    }

    public void transition(String runId, String status) {
        String outcome = switch (status) {
            case "QUEUED", "RUNNING", "CANCEL_REQUESTED", "STOPPING", "SUCCEEDED", "FAILED",
                    "CANCELLED", "EXPIRED", "LIMIT_EXHAUSTED", "INFRASTRUCTURE_FAILED",
                    "CONTEXT_OVERFLOW" -> status;
            default -> "UNKNOWN";
        };
        try {
            meters.counter("harness.run.transitions", "status", outcome).increment();
            LOG.info("run_transition runId={} status={}", runId, outcome);
        } catch (RuntimeException telemetryFailure) {
            LOG.warn("run transition telemetry failed runId={}", runId);
        }
    }

    private Attempt begin(String operation, String tool, String runId, String invocationId) {
        Observation observation = null;
        try {
            observation = Observation.createNotStarted("harness.attempt", observations)
                    .lowCardinalityKeyValue("operation", operation)
                    .lowCardinalityKeyValue("tool", tool)
                    .highCardinalityKeyValue("run.id", runId)
                    .highCardinalityKeyValue("invocation.id", invocationId)
                    .start();
            LOG.info("attempt_started runId={} invocationId={} operation={} tool={}",
                    runId, invocationId, operation, tool);
        } catch (RuntimeException telemetryFailure) {
            LOG.warn("attempt telemetry start failed runId={} invocationId={}", runId, invocationId);
        }
        return new Attempt(operation, tool, runId, invocationId, observation);
    }

    public final class Attempt implements AutoCloseable {
        private final String operation;
        private final String tool;
        private final String runId;
        private final String invocationId;
        private final Observation observation;
        private boolean success;

        private Attempt(String operation, String tool, String runId, String invocationId,
                Observation observation) {
            this.operation = operation;
            this.tool = tool;
            this.runId = runId;
            this.invocationId = invocationId;
            this.observation = observation;
        }

        public void succeeded() { success = true; }

        @Override public void close() {
            String outcome = success ? "success" : "failure";
            try {
                meters.counter("harness.attempts", "operation", operation, "tool", tool,
                        "outcome", outcome).increment();
            } catch (RuntimeException telemetryFailure) {
                LOG.warn("attempt counter failed runId={} invocationId={}", runId, invocationId);
            }
            if (observation != null) {
                try { observation.stop(); }
                catch (RuntimeException telemetryFailure) {
                    LOG.warn("attempt span export failed runId={} invocationId={}", runId, invocationId);
                }
            }
            LOG.info("attempt_finished runId={} invocationId={} operation={} tool={} outcome={}",
                    runId, invocationId, operation, tool, outcome);
        }
    }
}
