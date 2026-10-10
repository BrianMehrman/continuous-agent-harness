package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.workspace.SnapshotRef;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Workflow-owned snapshot, budgets, and lifecycle; external effects stay in Activities. */
public class CodingWorkflowImpl implements CodingWorkflow {
    private final RunActivities activities = Workflow.newActivityStub(RunActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(200))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build()).build());
    private final Map<String, AcceptedCancel> cancellations = new HashMap<>();
    private RunSpec spec;
    private String snapshot;
    private String status = "QUEUED";
    private long version;
    private long sequence;
    private long deadline;
    private boolean cancelRequested;
    private boolean expired;
    private CancellationScope deadlineScope;
    private String activeRunnerInvocation;

    @Override public RunView run(RunSpec input) {
        spec = input;
        snapshot = input.seedSnapshotSha256();
        while (!activities.acquireAdmission(input.runId())) {
            if (cancelRequested) return terminal("CANCELLED");
            Workflow.sleep(Duration.ofSeconds(1));
        }
        if (cancelRequested) return terminal("CANCELLED");
        deadline = Workflow.currentTimeMillis() + input.limits().runMillis();
        deadlineScope = Workflow.newCancellationScope(() -> {
            try {
                Workflow.sleep(Duration.ofMillis(input.limits().runMillis()));
                expired = true;
                if (activeRunnerInvocation != null) requestRunnerStop();
            } catch (CanceledFailure stopped) {
                // Terminal transition cancels only this timer scope.
            }
        });
        Async.procedure(deadlineScope::run);
        transition("RUNNING");
        String conversation = activities.startConversation(input);
        int toolCount = 0;
        int dispatchedModelAttempts = 0;
        boolean remindedOfProtocol = false;
        for (int turn = 1; turn <= input.limits().modelTurns(); turn++) {
            if (cancelRequested) return terminal("CANCELLED");
            if (expired || Workflow.currentTimeMillis() >= deadline) return terminal("EXPIRED");
            String invocation = input.runId() + ":turn-" + turn;
            ModelReply reply = null;
            for (int attempt = 1; attempt <= input.limits().perTurnModelAttempts(); attempt++) {
                if (expired || Workflow.currentTimeMillis() >= deadline) return terminal("EXPIRED");
                if (++dispatchedModelAttempts > input.limits().modelAttempts())
                    return terminal("LIMIT_EXHAUSTED");
                long modelDeadline = Math.min(deadline,
                        Workflow.currentTimeMillis() + input.limits().modelAttemptMillis());
                try {
                    reply = timedActivities(modelDeadline, 1).callModel(new ModelCall(input.runId(), invocation, attempt,
                            input.profileRevision(), conversation, modelDeadline));
                    break;
                } catch (ActivityFailure error) {
                    if (expired || Workflow.currentTimeMillis() >= deadline) return terminal("EXPIRED");
                    String failureType = modelFailureType(error);
                    if ("MODEL_CONTEXT_OVERFLOW".equals(failureType)) return terminal("CONTEXT_OVERFLOW");
                    if (!"MODEL_ATTEMPT_UNKNOWN".equals(failureType)) return terminal("INFRASTRUCTURE_FAILED");
                }
            }
            if (reply == null) return terminal("INFRASTRUCTURE_FAILED");
            if (Workflow.currentTimeMillis() >= deadline) return terminal("EXPIRED");
            if (reply.tools().isEmpty()) {
                if (remindedOfProtocol) return terminal("FAILED");
                remindedOfProtocol = true;
                conversation = activities.appendConversation(conversation, reply, java.util.List.of());
                continue;
            }
            var outcomes = new ArrayList<ToolOutcome>();
            for (int index = 0; index < reply.tools().size(); index++) {
                if (cancelRequested) return terminal("CANCELLED");
                if (++toolCount > input.limits().toolCalls()) return terminal("LIMIT_EXHAUSTED");
                var request = reply.tools().get(index);
                String toolInvocation = invocation + ":tool-" + (index + 1);
                long cap = switch (request.name()) {
                    case "run_tests", "build" -> input.limits().buildTestMillis();
                    case "submit" -> input.limits().evaluationMillis();
                    default -> input.limits().fileOperationMillis();
                };
                long toolDeadline = Math.min(deadline, Workflow.currentTimeMillis() + cap);
                activeRunnerInvocation = "run_tests".equals(request.name()) || "build".equals(request.name())
                        ? toolInvocation : null;
                ToolOutcome outcome;
                try {
                    outcome = timedActivities(toolDeadline, 2).invokeTool(new ToolInvocation(input.runId(),
                            toolInvocation, new SnapshotRef(snapshot), request, toolDeadline));
                } catch (ActivityFailure failure) {
                    if (cancelRequested) return terminal("CANCELLED");
                    if (expired || Workflow.currentTimeMillis() >= deadline) return terminal("EXPIRED");
                    activeRunnerInvocation = null;
                    return terminal("INFRASTRUCTURE_FAILED");
                }
                if (cancelRequested) return terminal("CANCELLED");
                if (Workflow.currentTimeMillis() >= deadline) return terminal("EXPIRED");
                activeRunnerInvocation = null;
                snapshot = outcome.snapshot().sha256();
                outcomes.add(outcome);
                if (outcome.failureCode() != null) return terminal("INFRASTRUCTURE_FAILED");
                if (outcome.attemptedSubmission() && outcome.passed()) return terminal("SUCCEEDED");
            }
            conversation = activities.appendConversation(conversation, reply, outcomes);
        }
        return terminal("LIMIT_EXHAUSTED");
    }

    @Override public CommandResult cancel(CancelCommand command) {
        var prior = cancellations.get(command.commandId());
        if (prior != null) return prior.expectedVersion() == command.expectedVersion() ? prior.result()
                : new CommandResult(spec.runId(), command.commandId(), "REJECTED_CONFLICT");
        if (terminalStatus(status)) return new CommandResult(spec.runId(), command.commandId(), "REJECTED_TERMINAL");
        if (command.expectedVersion() != version)
            return new CommandResult(spec.runId(), command.commandId(), "REJECTED_VERSION");
        cancelRequested = true;
        transition("CANCEL_REQUESTED");
        if (activeRunnerInvocation != null) requestRunnerStop();
        var result = new CommandResult(spec.runId(), command.commandId(), "APPLIED");
        cancellations.put(command.commandId(), new AcceptedCancel(command.expectedVersion(), result));
        return result;
    }

    @Override public RunView current() {
        return new RunView(spec.runId(), version, status, snapshot, sequence, false,
                Instant.ofEpochMilli(Workflow.currentTimeMillis()), null);
    }

    private RunView terminal(String endStatus) {
        if (deadlineScope != null) deadlineScope.cancel();
        if (("CANCELLED".equals(endStatus) || "EXPIRED".equals(endStatus))
                && activeRunnerInvocation != null) {
            transition("STOPPING");
            while (activeRunnerInvocation != null) {
                try {
                    if (activities.cancelRunner(activeRunnerInvocation)) activeRunnerInvocation = null;
                } catch (ActivityFailure unavailable) {
                    // Keep the slot and retry reconciliation while cleanup is unconfirmed.
                }
                if (activeRunnerInvocation != null) Workflow.sleep(Duration.ofSeconds(1));
            }
        }
        transition(endStatus);
        activities.releaseAdmission(spec.runId());
        return current();
    }

    private void requestRunnerStop() {
        try {
            activities.cancelRunner(activeRunnerInvocation);
        } catch (ActivityFailure unavailable) {
            // The workflow will reconcile this request before its terminal transition.
        }
    }

    private void transition(String next) {
        status = next;
        version++;
        sequence++;
        activities.publish(spec.runId(), sequence, version, status, snapshot, null);
    }

    private static boolean terminalStatus(String status) {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "LIMIT_EXHAUSTED",
                    "INFRASTRUCTURE_FAILED", "CONTEXT_OVERFLOW" -> true;
            default -> false;
        };
    }

    private static String modelFailureType(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ApplicationFailure application) return application.getType();
        }
        return "OTHER";
    }

    private RunActivities timedActivities(long absoluteDeadline, int maxAttempts) {
        long millis = Math.max(1, absoluteDeadline - Workflow.currentTimeMillis());
        return Workflow.newActivityStub(RunActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMillis(millis))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(maxAttempts).build())
                        .build());
    }

    private record AcceptedCancel(long expectedVersion, CommandResult result) {}
}
