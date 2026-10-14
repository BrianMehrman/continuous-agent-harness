package com.brianmehrman.harness.runner;

import com.brianmehrman.harness.workspace.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Runs the real runner supervisor inside a single-role test context. */
public final class TestRunnerSupervisor implements AutoCloseable {
    private final RunnerSupervisor supervisor;

    public TestRunnerSupervisor(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            WorkspaceStore workspaces, ObjectProvider<RunnerBoundary> boundary) {
        supervisor = new RunnerSupervisor(jdbc, transactions, workspaces, boundary);
        supervisor.start();
    }

    @Override public void close() { supervisor.shutdown(); }
}
