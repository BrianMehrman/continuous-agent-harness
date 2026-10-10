package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runs.RunCommandService;
import com.brianmehrman.harness.runs.StartCommand;
import com.brianmehrman.harness.runs.WorkflowGateway;
import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CommandResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class RunAdmissionStoreIT {
    @Autowired RunCommandService commands;
    @Autowired ProfileRevisionStore profiles;
    @Autowired RunAdmissionStore admissions;
    private final List<String> createdRuns = new ArrayList<>();

    @AfterEach void releaseTestReservations() {
        for (String runId : createdRuns) admissions.releaseAfterTerminalTransition(runId);
    }

    private String run() {
        String profile = "profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        String runId = commands.start(new StartCommand("start-" + UUID.randomUUID(),
                "task-tracker-v1", profile, Limits.codingDefaults())).runId();
        createdRuns.add(runId);
        return runId;
    }

    @Test void oneRunOwnsTheSlotAcrossRepeatedAcquisitionUntilItsTerminalRelease() {
        String first = run();
        String second = run();
        assertTrue(admissions.acquire(first));
        assertTrue(admissions.acquire(first));
        assertFalse(admissions.acquire(second));
        assertFalse(admissions.releaseAfterTerminalTransition(second));
        assertFalse(admissions.acquire(second));
        assertTrue(admissions.releaseAfterTerminalTransition(first));
        assertTrue(admissions.acquire(second));
        assertTrue(admissions.releaseAfterTerminalTransition(second));
    }

    @Test void reconcilesOnlyAConfirmedCompletedWorkflow() {
        String runId = run();
        assertTrue(admissions.acquire(runId));
        assertFalse(admissions.reconcileCompleted(gateway(runId, "RUNNING")));
        assertEquals(runId, admissions.reservedRunId().orElseThrow());
        assertFalse(admissions.reconcileCompleted(gateway(runId, null)));
        assertEquals(runId, admissions.reservedRunId().orElseThrow());
        assertTrue(admissions.reconcileCompleted(gateway(runId, "WORKFLOW_EXECUTION_STATUS_COMPLETED")));
        assertTrue(admissions.reservedRunId().isEmpty());
    }

    private static WorkflowGateway gateway(String runId, String status) {
        return new WorkflowGateway() {
            @Override public StartReceipt start(RunSpec spec) { throw new AssertionError(); }
            @Override public CommandResult cancel(String id, CancelCommand command) {
                throw new AssertionError();
            }
            @Override public WorkflowDescription describe(String id) {
                assertEquals(runId, id);
                return status == null ? null : new WorkflowDescription(id, "a".repeat(64), 1,
                        status, "b".repeat(64), null);
            }
        };
    }
}
