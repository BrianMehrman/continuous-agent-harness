package com.brianmehrman.harness.recovery;

import com.brianmehrman.harness.HarnessApplication;
import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.runs.CommandDispatcher;
import com.brianmehrman.harness.runs.RunEvent;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import com.brianmehrman.harness.runs.TemporalWorkflowGateway;
import com.brianmehrman.harness.runs.WorkflowGateway;
import com.brianmehrman.harness.workspace.SnapshotRef;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import com.brianmehrman.harness.workspace.WriteRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Child process halted by the test only after a persisted semantic boundary is reached. */
public final class DurableBoundaryProcess {
    private DurableBoundaryProcess() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path marker = Path.of(args[1]);
        int springStart = mode.equals("command") ? 3 : 5;
        try (ConfigurableApplicationContext context = SpringApplication.run(HarnessApplication.class,
                Arrays.copyOfRange(args, springStart, args.length))) {
            switch (mode) {
                case "source" -> {
                    context.getBean(WorkspaceStore.class).write(new WriteRequest(args[2], args[4],
                            new SnapshotRef(args[3]), "src/test/java/ProcessProbeTest.java", "absent",
                            "class ProcessProbeTest {}"));
                    markAndWait(marker);
                }
                case "command" -> {
                    WorkflowGateway delegate = context.getBean(TemporalWorkflowGateway.class);
                    WorkflowGateway pausing = new WorkflowGateway() {
                        @Override public StartReceipt start(RunSpec spec) {
                            StartReceipt receipt = delegate.start(spec);
                            try { markAndWait(marker); }
                            catch (Exception interrupted) { throw new IllegalStateException(interrupted); }
                            return receipt;
                        }
                        @Override public CommandResult cancel(String runId, CancelCommand command) {
                            return delegate.cancel(runId, command);
                        }
                        @Override public WorkflowDescription describe(String runId) {
                            return delegate.describe(runId);
                        }
                        @Override public long authoritativeVersion(String runId) {
                            return delegate.authoritativeVersion(runId);
                        }
                    };
                    new CommandDispatcher(context.getBean(JdbcTemplate.class),
                            context.getBean(PlatformTransactionManager.class), pausing)
                            .dispatchOnce(args[2]);
                }
                case "projection" -> {
                    var event = new RunEvent(args[2], 1, args[3], "STATE_CHANGED", args[4]);
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                            .executeWithoutResult(status -> {
                                context.getBean(RunProjectionRepository.class).append(event);
                                try { markAndWait(marker); }
                                catch (Exception interrupted) { throw new IllegalStateException(interrupted); }
                            });
                }
                default -> throw new IllegalArgumentException("unknown boundary mode");
            }
        }
    }

    private static void markAndWait(Path marker) throws Exception {
        Files.writeString(marker, "reached");
        Thread.sleep(300_000);
    }
}
