package com.brianmehrman.harness.runs;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class CommandDeliveryIT {
    @Autowired RunCommandService commands;
    @Autowired ProfileRevisionStore profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired BlobStore blobs;
    @Autowired RunProjectionRepository projections;

    private StartCommand request() {
        String id = "profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(id, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        return new StartCommand("start-" + UUID.randomUUID(), "task-tracker-v1", id, Limits.codingDefaults());
    }

    @Test void lostStartAcknowledgmentDoesNotCreateAnotherRun() {
        StartCommand command = request();
        StartResult accepted = commands.start(command);
        var gateway = new RecordingWorkflowGateway();
        gateway.failOnceAfterCreatingWorkflow = true;
        var dispatcher = new CommandDispatcher(jdbc, manager, gateway);
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOnce(accepted.runId()));
        assertNull(jdbc.queryForObject("select delivered_at from run_command where command_id=?",
                java.sql.Timestamp.class, command.commandId()));
        jdbc.update("update run_command set next_attempt_at=now() where command_id=?", command.commandId());
        assertTrue(dispatcher.dispatchOnce(accepted.runId()));
        assertEquals(accepted.runId(), commands.start(command).runId());
        assertEquals(1, gateway.createdWorkflowCount);
        assertNotNull(jdbc.queryForObject("select delivered_at from run_command where command_id=?",
                java.sql.Timestamp.class, command.commandId()));
    }

    @Test void retriedStartWithoutTemporalHistoryDoesNotReuseTheRunId() {
        StartResult accepted = commands.start(request());
        jdbc.update("update run_command set attempt_count=1 where run_id=? and kind='START'",
                accepted.runId());
        var gateway = new RecordingWorkflowGateway();
        var dispatcher = new CommandDispatcher(jdbc, manager, gateway);

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOnce(accepted.runId()));
        assertEquals(0, gateway.createdWorkflowCount);
        assertEquals("START_OUTCOME_UNKNOWN", jdbc.queryForObject(
                "select last_error_code from run_command where run_id=? and kind='START'",
                String.class, accepted.runId()));
        assertNull(jdbc.queryForObject("select delivered_at from run_command where run_id=? and kind='START'",
                java.sql.Timestamp.class, accepted.runId()));
    }

    @Test void cancellationWaitsForStartDelivery() {
        StartResult run = commands.start(request());
        CancelCommand cancel = new CancelCommand("cancel-" + UUID.randomUUID(), 0);
        CommandResult accepted = commands.cancel(run.runId(), cancel);
        assertEquals("ACCEPTED", accepted.deliveryStatus());
        var gateway = new RecordingWorkflowGateway();
        var dispatcher = new CommandDispatcher(jdbc, manager, gateway);
        assertTrue(dispatcher.dispatchOnce(run.runId()));
        assertEquals(0, gateway.cancelCount);
        assertTrue(dispatcher.dispatchOnce(run.runId()));
        assertEquals(1, gateway.cancelCount);
        assertEquals(run.runId(), gateway.lastCancelledRun);
    }

    @Test void activeClaimLeasePreventsConcurrentDispatchUntilItExpires() {
        StartResult run = commands.start(request());
        jdbc.update("update run_command set lease_owner='other-worker', lease_until=now()+interval '1 hour' where run_id=?",
                run.runId());
        var gateway = new RecordingWorkflowGateway();
        var dispatcher = new CommandDispatcher(jdbc, manager, gateway);
        assertFalse(dispatcher.dispatchOnce(run.runId()));
        jdbc.update("update run_command set lease_until=now()-interval '1 second' where run_id=?", run.runId());
        assertTrue(dispatcher.dispatchOnce(run.runId()));
        assertEquals(1, gateway.createdWorkflowCount);
    }

    @Test void expiredClaimCannotAcknowledgeAnotherWorkersLease() {
        StartResult run = commands.start(request());
        var gateway = new RecordingWorkflowGateway();
        gateway.afterStart = () -> jdbc.update("update run_command set lease_owner='new-worker', " +
                "lease_until=now()+interval '1 minute' where run_id=?", run.runId());
        var dispatcher = new CommandDispatcher(jdbc, manager, gateway);
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchOnce(run.runId()));
        assertNull(jdbc.queryForObject("select delivered_at from run_command where run_id=?",
                java.sql.Timestamp.class, run.runId()));
        assertEquals("new-worker", jdbc.queryForObject("select lease_owner from run_command where run_id=?",
                String.class, run.runId()));
    }

    @Test void repeatedEventIsNoOpAndChangedReuseIsAnIntegrityError() {
        StartResult run = commands.start(request());
        String snapshot = jdbc.queryForObject("select snapshot_sha256 from run_projection where run_id=?",
                String.class, run.runId());
        String payload = eventPayload(1, "RUNNING", snapshot);
        RunEvent event = new RunEvent(run.runId(), 1, "event-" + UUID.randomUUID(), "STATE_CHANGED", payload);
        projections.append(event);
        projections.append(event);
        assertEquals(1, jdbc.queryForObject("select count(*) from run_event where event_id=?",
                Integer.class, event.eventId()));
        assertEquals("RUNNING", projections.view(run.runId()).status());
        assertThrows(IllegalStateException.class, () -> projections.append(new RunEvent(run.runId(),
                1, event.eventId(), event.type(), eventPayload(1, "STOPPING", snapshot))));
    }

    @Test void sequenceGapIsStaleUntilMissingEventArrives() {
        StartResult run = commands.start(request());
        String snapshot = jdbc.queryForObject("select snapshot_sha256 from run_projection where run_id=?",
                String.class, run.runId());
        RunEvent second = new RunEvent(run.runId(), 2, "event-" + UUID.randomUUID(),
                "STATE_CHANGED", eventPayload(2, "RUNNING", snapshot));
        projections.append(second);
        assertTrue(projections.view(run.runId()).stale());
        assertEquals(0, projections.view(run.runId()).lastSequence());
        projections.append(new RunEvent(run.runId(), 1, "event-" + UUID.randomUUID(),
                "STATE_CHANGED", eventPayload(1, "QUEUED", snapshot)));
        assertFalse(projections.view(run.runId()).stale());
        assertEquals(2, projections.view(run.runId()).lastSequence());
        assertEquals(2, projections.view(run.runId()).version());
        assertEquals("RUNNING", projections.view(run.runId()).status());
    }

    @Test void eventAndProjectionRollBackTogether() {
        StartResult run = commands.start(request());
        String snapshot = jdbc.queryForObject("select snapshot_sha256 from run_projection where run_id=?",
                String.class, run.runId());
        RunEvent event = new RunEvent(run.runId(), 1, "event-" + UUID.randomUUID(),
                "STATE_CHANGED", eventPayload(1, "RUNNING", snapshot));
        var outer = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () -> outer.execute(status -> {
            projections.append(event);
            throw new IllegalStateException("injected projection rollback");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from run_event where event_id=?",
                Integer.class, event.eventId()));
        assertEquals(0, projections.view(run.runId()).lastSequence());
    }

    @Test void informationalEventMayKeepTheSameStateVersion() {
        StartResult run = commands.start(request());
        String snapshot = jdbc.queryForObject("select snapshot_sha256 from run_projection where run_id=?",
                String.class, run.runId());
        projections.append(new RunEvent(run.runId(), 1, "event-" + UUID.randomUUID(),
                "STATE_CHANGED", eventPayload(1, "RUNNING", snapshot)));
        projections.append(new RunEvent(run.runId(), 2, "event-" + UUID.randomUUID(),
                "MODEL_REQUESTED", eventPayload(1, "RUNNING", snapshot)));
        assertEquals(2, projections.view(run.runId()).lastSequence());
        assertEquals(1, projections.view(run.runId()).version());
        assertFalse(projections.view(run.runId()).stale());
    }

    @Test void reconciliationExposesLagUntilMatchingEventsArrive() {
        StartResult run = commands.start(request());
        String snapshot = jdbc.queryForObject("select snapshot_sha256 from run_projection where run_id=?",
                String.class, run.runId());
        String input = jdbc.queryForObject("select input_sha256 from run_request where run_id=?",
                String.class, run.runId());
        var gateway = new RecordingWorkflowGateway();
        gateway.workflows.put(run.runId(), input);
        gateway.descriptionVersion = 2;
        projections.reconcile(run.runId(), gateway);
        assertTrue(projections.view(run.runId()).stale());
        assertEquals(2, projections.view(run.runId()).version());
        projections.append(new RunEvent(run.runId(), 1, "event-" + UUID.randomUUID(),
                "STATE_CHANGED", eventPayload(1, "RUNNING", snapshot)));
        assertTrue(projections.view(run.runId()).stale());
        projections.append(new RunEvent(run.runId(), 2, "event-" + UUID.randomUUID(),
                "STATE_CHANGED", eventPayload(2, "RUNNING", snapshot)));
        assertFalse(projections.view(run.runId()).stale());
        gateway.workflows.put(run.runId(), "b".repeat(64));
        assertThrows(IllegalStateException.class, () -> projections.reconcile(run.runId(), gateway));
    }

    private String eventPayload(long version, String status, String snapshot) {
        String json = "{\"version\":" + version + ",\"status\":\"" + status +
                "\",\"snapshotSha256\":\"" + snapshot + "\"}";
        return blobs.put("application/vnd.harness.run-event+json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingWorkflowGateway implements WorkflowGateway {
        private final Map<String, String> workflows = new HashMap<>();
        private boolean failOnceAfterCreatingWorkflow;
        private int createdWorkflowCount;
        private int cancelCount;
        private String lastCancelledRun;
        private long descriptionVersion;
        private Runnable afterStart = () -> {};

        @Override public StartReceipt start(RunSpec spec) {
            String existing = workflows.putIfAbsent(spec.runId(), spec.inputSha256());
            if (existing == null) createdWorkflowCount++;
            if (failOnceAfterCreatingWorkflow) {
                failOnceAfterCreatingWorkflow = false;
                throw new IllegalStateException("injected lost start acknowledgment");
            }
            afterStart.run();
            return new StartReceipt(existing == null ? StartOutcome.STARTED : StartOutcome.ALREADY_EXISTS,
                    existing == null ? spec.inputSha256() : existing);
        }

        @Override public CommandResult cancel(String runId, CancelCommand command) {
            cancelCount++;
            lastCancelledRun = runId;
            return new CommandResult(runId, command.commandId(), "APPLIED");
        }

        @Override public WorkflowDescription describe(String runId) {
            String input = workflows.get(runId);
            return input == null ? null : new WorkflowDescription(runId, input, descriptionVersion,
                    "QUEUED", null, null);
        }
    }
}
