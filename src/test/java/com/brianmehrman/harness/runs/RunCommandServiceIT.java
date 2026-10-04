package com.brianmehrman.harness.runs;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class RunCommandServiceIT {
    @Autowired RunCommandService commands;
    @Autowired ProfileRevisionStore profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private StartCommand startCommand() {
        String id = "profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(id, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        return new StartCommand("start-" + UUID.randomUUID(), "task-tracker-v1", id, Limits.codingDefaults());
    }

    @Test void concurrentDuplicateStartCreatesOneRunAndOneOutboxCommand() throws Exception {
        StartCommand request = startCommand();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> commands.start(request));
            var second = pool.submit(() -> commands.start(request));
            StartResult one = first.get();
            StartResult two = second.get();
            assertEquals(one, two);
            assertEquals("ACCEPTED", one.deliveryStatus());
            assertEquals(1, jdbc.queryForObject("select count(*) from run_request where command_id=?",
                    Integer.class, request.commandId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from run_command where command_id=? and kind='START'",
                    Integer.class, request.commandId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from run_workspace where run_id=?",
                    Integer.class, one.runId()));
            assertEquals(request.profileRevision(), jdbc.queryForObject(
                    "select spec_json->>'profileRevision' from run_request where run_id=?", String.class, one.runId()));
        }
    }

    @Test void conflictingStartCommandReuseIsRejectedWithoutAnotherRun() {
        StartCommand request = startCommand();
        StartResult accepted = commands.start(request);
        StartCommand changed = new StartCommand(request.commandId(), request.benchmarkVersion(),
                request.profileRevision(), Limits.codingDefaults().withModelTurns(3));
        assertThrows(IllegalArgumentException.class, () -> commands.start(changed));
        assertEquals(1, jdbc.queryForObject("select count(*) from run_request where command_id=?",
                Integer.class, request.commandId()));
        assertEquals(accepted.runId(), commands.start(request).runId());
    }

    @Test void acceptanceRollbackLeavesNoSeedOrCommand() {
        StartCommand request = startCommand();
        var transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            commands.start(request);
            throw new IllegalStateException("injected rollback");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from run_request where command_id=?",
                Integer.class, request.commandId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from run_command where command_id=?",
                Integer.class, request.commandId()));
    }

    @Test void cancelIsAcceptedDurablyAndRepeatedIdIsStable() {
        StartResult run = commands.start(startCommand());
        CancelCommand cancel = new CancelCommand("cancel-" + UUID.randomUUID(), 0);
        var first = commands.cancel(run.runId(), cancel);
        assertEquals("ACCEPTED", first.deliveryStatus());
        assertEquals(first, commands.cancel(run.runId(), cancel));
        assertThrows(IllegalArgumentException.class,
                () -> commands.cancel(run.runId(), new CancelCommand(cancel.commandId(), 1)));
        assertEquals(1, jdbc.queryForObject("select count(*) from run_command where command_id=? and kind='CANCEL'",
                Integer.class, cancel.commandId()));
    }
}
