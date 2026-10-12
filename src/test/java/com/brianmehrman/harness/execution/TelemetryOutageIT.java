package com.brianmehrman.harness.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.brianmehrman.harness.runs.TemporalWorkflowGateway;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.worker.WorkerFactory;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** A disconnected OTLP receiver cannot change a scripted coding run or its durable history. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "harness.api.dispatch-enabled=false",
        "management.otlp.metrics.export.enabled=true",
        "management.otlp.metrics.export.url=http://127.0.0.1:9/v1/metrics",
        "management.otlp.metrics.export.step=1s",
        "management.tracing.export.otlp.enabled=true",
        "management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces",
        "management.tracing.sampling.probability=1.0"})
@ActiveProfiles("api")
class TelemetryOutageIT {
    @Autowired WorkflowClient client;
    @Autowired RunTelemetry telemetry;
    @Autowired MeterRegistry meters;

    @Test
    void scriptedCodingRunSucceedsAndHistoryRemainsReadableWithDisconnectedExporter() throws Exception {
        String queue = "telemetry-outage-" + UUID.randomUUID();
        String runId = "run-" + UUID.randomUUID().toString().replace("-", "") + "a".repeat(32);
        var factory = WorkerFactory.newInstance(client);
        try {
            var worker = factory.newWorker(queue);
            worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
            worker.registerActivitiesImplementations(new CodingWorkflowSmokeIT.FixtureActivities(telemetry));
            factory.start();
            var spec = new RunSpec(runId, "start-telemetry-outage", "f".repeat(64),
                    "task-tracker-v1", "0".repeat(64), "evaluator-v1", "profile-scripted",
                    "1".repeat(64), "b".repeat(64), Limits.codingDefaults());
            assertThat(new TemporalWorkflowGateway(client, queue).start(spec).outcome().name())
                    .isEqualTo("STARTED");
            RunView result = WorkflowStub.fromTyped(client.newWorkflowStub(CodingWorkflow.class, runId))
                    .getResult(30, TimeUnit.SECONDS, RunView.class);
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            assertThat(meters.find("harness.attempts").counters()).isNotEmpty();
            // Let the configured push step attempt export to the disconnected receiver.
            Thread.sleep(1_500);
            assertThat(client.fetchHistory(runId).getHistory().getEventsCount()).isGreaterThan(0);
            assertThat(client.newUntypedWorkflowStub(runId).describe().getStatus().name())
                    .isEqualTo("WORKFLOW_EXECUTION_STATUS_COMPLETED");
        } finally {
            factory.shutdownNow();
            factory.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
