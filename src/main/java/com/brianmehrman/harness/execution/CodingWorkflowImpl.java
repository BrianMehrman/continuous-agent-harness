package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.workspace.SnapshotRef;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;
import io.temporal.common.VersioningBehavior;
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
    private final Map<String, AcceptedControl> controls = new HashMap<>();
    private RunSpec spec;
    private String snapshot;
    private String status = "QUEUED";
    private long version;
    private long sequence;
    private boolean cancelRequested;
    private String activeRunnerInvocation;
    private PausePolicy policy;
    private int turnsGranted, toolsGranted, attemptsGranted;
    private long activeMillisGranted, activeMillisUsed, segmentStartedAt;
    private int resumesApplied, extraTurnsApplied, extraToolCallsApplied, extraMinutesApplied;
    private boolean paused;
    private ResumeCommand resumeRequest;
    private String pauseReason;
    private boolean stopRequested;
    private String lastNote;
    private CostTerms costTerms;
    private Long limitMicros;
    private long spentMicros, estimatedMicros;

    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    @Override public RunView run(RunSpec input) {
        spec = input;
        snapshot = input.seedSnapshotSha256();
        policy = input.policy();
        costTerms = input.terms() == null ? null : input.terms().cost();
        limitMicros = costTerms == null ? null : costTerms.limitMicros();
        turnsGranted = input.limits().modelTurns();
        toolsGranted = input.limits().toolCalls();
        attemptsGranted = input.limits().modelAttempts();
        activeMillisGranted = input.limits().runMillis();
        RunView refused = admit();
        if (refused != null) return refused;
        transition("RUNNING");
        String conversation = activities.startConversation(input);
        int turn = 0;
        int toolCount = 0;
        int dispatched = 0;
        while (true) {
            if (cancelRequested) return terminal("CANCELLED");
            String limit = turn >= turnsGranted || dispatched + input.limits().perTurnModelAttempts() > attemptsGranted
                    ? "TURN_LIMIT" : toolCount >= toolsGranted ? "TOOL_LIMIT" : outOfTime() ? "TIME_LIMIT" : null;
            if (limit != null) {
                Resumed resumed = pause(limit, null, conversation, turn, toolCount);
                if (resumed.terminal() != null) return resumed.terminal();
                conversation = resumed.conversation();
                continue;
            }
            if (nextCallExceedsLimit()) {
                Resumed resumed = pause("COST_LIMIT", null, conversation, turn, toolCount);
                if (resumed.terminal() != null) return resumed.terminal();
                conversation = resumed.conversation();
                continue;
            }
            turn++;
            String invocation = input.runId() + ":turn-" + turn;
            ModelReply reply = null;
            for (int attempt = 1; attempt <= input.limits().perTurnModelAttempts(); attempt++) {
                if (cancelRequested) return terminal("CANCELLED");
                if (attempt > 1 && nextCallExceedsLimit()) {
                    Resumed resumed = pause("COST_LIMIT", null, conversation, turn, toolCount);
                    if (resumed.terminal() != null) return resumed.terminal();
                    conversation = resumed.conversation();
                }
                dispatched++;
                long modelDeadline = Workflow.currentTimeMillis() + input.limits().modelAttemptMillis();
                try {
                    // A worker restart may redeliver this Activity to reconcile its durable receipt.
                    reply = timedActivities(modelDeadline, 2).callModel(new ModelCall(input.runId(), invocation, attempt,
                            input.profileRevision(), conversation, modelDeadline,
                            input.terms() == null ? null : input.terms().tools()));
                    addCharge(costTerms == null ? null : costTerms.charge(reply));
                    break;
                } catch (ActivityFailure error) {
                    if (cancelRequested) return terminal("CANCELLED");
                    String failureType = modelFailureType(error);
                    if ("MODEL_CONTEXT_OVERFLOW".equals(failureType)) return terminal("CONTEXT_OVERFLOW");
                    if (!"MODEL_ATTEMPT_UNKNOWN".equals(failureType)) return terminal("INFRASTRUCTURE_FAILED");
                    addCharge(costTerms == null ? null : costTerms.worstCase());
                }
            }
            if (reply == null) return terminal("INFRASTRUCTURE_FAILED");
            if ("repository-v1".equals(input.benchmarkVersion()))
                progress("MODEL_RECORDED", "model", "Model response recorded", null);
            var outcomes = new ArrayList<ToolOutcome>();
            String budgetPause = null;
            for (int index = 0; index < reply.tools().size(); index++) {
                if (cancelRequested) return terminal("CANCELLED");
                var request = reply.tools().get(index);
                String toolInvocation = invocation + ":tool-" + (index + 1);
                if (budgetPause == null)
                    budgetPause = toolCount >= toolsGranted ? "TOOL_LIMIT" : outOfTime() ? "TIME_LIMIT" : null;
                if (budgetPause != null) {
                    outcomes.add(notRun(toolInvocation, request));
                    continue;
                }
                toolCount++;
                long cap = switch (request.name()) {
                    case "run_tests", "build" -> input.limits().buildTestMillis();
                    case "submit" -> input.limits().evaluationMillis();
                    default -> input.limits().fileOperationMillis();
                };
                long toolDeadline = Workflow.currentTimeMillis() + cap;
                activeRunnerInvocation = "run_tests".equals(request.name()) || "build".equals(request.name())
                        ? toolInvocation : null;
                ToolOutcome outcome;
                try {
                    outcome = timedActivities(toolDeadline, 2).invokeTool(new ToolInvocation(input.runId(),
                            toolInvocation, new SnapshotRef(snapshot), request, toolDeadline,
                            input.terms() == null ? null : input.terms().tools()));
                } catch (ActivityFailure failure) {
                    if (cancelRequested) return terminal("CANCELLED");
                    activeRunnerInvocation = null;
                    return terminal("INFRASTRUCTURE_FAILED");
                }
                if (cancelRequested) return terminal("CANCELLED");
                activeRunnerInvocation = null;
                snapshot = outcome.snapshot().sha256();
                outcomes.add(outcome);
                if ("repository-v1".equals(input.benchmarkVersion())) {
                    String stage = switch (request.name()) {
                        case "run_tests", "build" -> "check";
                        case "submit" -> "submit";
                        default -> "tool";
                    };
                    progress("TOOL_RECORDED", stage, request.name() + " result recorded", null);
                }
                if (outcome.failureCode() != null) return terminal("INFRASTRUCTURE_FAILED");
                if (outcome.attemptedSubmission() && outcome.passed())
                    return terminal("repository-v1".equals(input.benchmarkVersion()) ? "READY_FOR_REVIEW" : "SUCCEEDED");
            }
            ConversationStep step = activities.appendConversation(conversation, reply, outcomes);
            conversation = step.blobId();
            if (step.note() != null) lastNote = step.note();
            if (step.nudged() != null) {
                var nudged = new java.util.LinkedHashMap<String, Object>();
                nudged.put("signal", step.nudged());
                nudged.put("turn", turn);
                nudged.put("summary", "Nudged: " + step.nudged().replace('_', ' ').toLowerCase());
                event("NUDGED", nudged);
            }
            String reason = budgetPause != null ? budgetPause : step.stall() != null ? "STALLED" : null;
            if (reason != null) {
                Resumed resumed = pause(reason, step.stall(), conversation, turn, toolCount);
                if (resumed.terminal() != null) return resumed.terminal();
                conversation = resumed.conversation();
            }
        }
    }

    @Override public CommandResult cancel(CancelCommand command) {
        var prior = cancellations.get(command.commandId());
        if (prior != null) return prior.expectedVersion() == command.expectedVersion() ? prior.result()
                : new CommandResult(spec.runId(), command.commandId(), "REJECTED_CONFLICT");
        if (RunStatus.isTerminal(status)) return new CommandResult(spec.runId(), command.commandId(), "REJECTED_TERMINAL");
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

    private void progress(String type, String stage, String summary, String artifactDigest) {
        sequence++;
        activities.publishProgress(spec.runId(), sequence, version, status, snapshot,
                type, stage, summary, artifactDigest);
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

    private record Resumed(RunView terminal, String conversation) {}
    private record AcceptedControl(Object payload, CommandResult result) {}

    private RunView admit() {
        while (!activities.acquireAdmission(spec.runId())) {
            if (cancelRequested) return terminal("CANCELLED");
            Workflow.sleep(Duration.ofSeconds(1));
        }
        if (cancelRequested) return terminal("CANCELLED");
        segmentStartedAt = Workflow.currentTimeMillis();
        return null;
    }

    private boolean outOfTime() {
        return activeMillisUsed + Workflow.currentTimeMillis() - segmentStartedAt >= activeMillisGranted;
    }

    private boolean nextCallExceedsLimit() {
        if (costTerms == null || limitMicros == null) return false;
        long effective = costTerms.effectiveLimit(limitMicros);
        long worst = costTerms.worstCase().micros();
        return spentMicros > effective || worst > effective - spentMicros;
    }

    private void addCharge(CostTerms.Charge charge) {
        if (charge == null) return;
        spentMicros = Math.addExact(spentMicros, charge.micros());
        if (charge.estimated()) estimatedMicros = Math.addExact(estimatedMicros, charge.micros());
    }

    private Resumed pause(String reason, WorkingMemory.StallVerdict stall, String conversation, int turn, int tools) {
        activeMillisUsed += Workflow.currentTimeMillis() - segmentStartedAt;
        boolean exhausted = resumesApplied >= policy.maxResumes();
        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("reason", reason);
        detail.put("signal", stall == null ? null : stall.signal());
        detail.put("sinceTurn", stall == null ? null : stall.sinceTurn());
        detail.put("evidence", stall == null ? java.util.List.of() : stall.evidence());
        detail.put("turnsUsed", turn);
        detail.put("turnsGranted", turnsGranted);
        detail.put("toolCallsUsed", tools);
        detail.put("toolCallsGranted", toolsGranted);
        detail.put("activeMillisUsed", activeMillisUsed);
        detail.put("activeMillisGranted", activeMillisGranted);
        detail.put("cost", costDetail());
        detail.put("note", lastNote);
        detail.put("waitUntil", exhausted ? null
                : Instant.ofEpochMilli(Workflow.currentTimeMillis() + policy.waitMillis()).toString());
        detail.put("summary", "Paused: " + reason.replace('_', ' ').toLowerCase());
        if (exhausted) {
            // No resume could be accepted: record the pause evidence, then end with the pause reason without waiting.
            event("PAUSED", detail);
            var stopped = new java.util.LinkedHashMap<String, Object>();
            stopped.put("reason", reason);
            stopped.put("byOperator", false);
            stopped.put("summary", "Stopped: no resumes left");
            event("STOPPED", stopped);
            return new Resumed(terminal(pauseOutcome(reason)), null);
        }
        activities.releaseAdmission(spec.runId());
        // Accept commands from the moment the PAUSED status can be observed.
        resumeRequest = null;
        stopRequested = false;
        paused = true;
        pauseReason = reason;
        transition("PAUSED");
        event("PAUSED", detail);
        boolean signalled = Workflow.await(Duration.ofMillis(policy.waitMillis()),
                () -> resumeRequest != null || stopRequested || cancelRequested);
        paused = false;
        if (cancelRequested) return new Resumed(terminal("CANCELLED"), null);
        if (!signalled || stopRequested) {
            var stopped = new java.util.LinkedHashMap<String, Object>();
            stopped.put("reason", reason);
            stopped.put("byOperator", signalled);
            stopped.put("summary", signalled ? "Stopped by operator" : "Stopped: waiting limit reached");
            event("STOPPED", stopped);
            return new Resumed(terminal(pauseOutcome(reason)), null);
        }
        ResumeCommand resume = resumeRequest;
        turnsGranted += resume.extraTurns();
        attemptsGranted += 2 * resume.extraTurns();
        toolsGranted += resume.extraToolCalls();
        activeMillisGranted += resume.extraMinutes() * 60_000L;
        if (limitMicros != null) limitMicros = Math.addExact(limitMicros, resume.extraCostMicros());
        String next = activities.resumeConversation(conversation, resume.hint());
        var resumed = new java.util.LinkedHashMap<String, Object>();
        resumed.put("commandId", resume.commandId());
        resumed.put("extraTurns", resume.extraTurns());
        resumed.put("extraToolCalls", resume.extraToolCalls());
        resumed.put("extraMinutes", resume.extraMinutes());
        resumed.put("extraCostMicros", resume.extraCostMicros());
        resumed.put("hint", resume.hint());
        resumed.put("summary", resume.hint() == null ? "Resumed with more budget" : "Resumed with an operator hint");
        event("RESUMED", resumed);
        transition("QUEUED");
        RunView refused = admit();
        if (refused != null) return new Resumed(refused, null);
        transition("RUNNING");
        return new Resumed(null, next);
    }

    private Map<String, Object> costDetail() {
        if (costTerms == null || limitMicros == null && costTerms.prices().free()) return null;
        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("spentMicros", spentMicros);
        detail.put("estimatedMicros", estimatedMicros);
        detail.put("effectiveLimitMicros", limitMicros == null ? null : costTerms.effectiveLimit(limitMicros));
        detail.put("limitMicros", limitMicros);
        detail.put("bufferPercent", costTerms.bufferPercent());
        detail.put("nextCallWorstCaseMicros", costTerms.worstCase().micros());
        return detail;
    }

    private static String pauseOutcome(String reason) {
        return switch (reason) {
            case "STALLED" -> "STALLED";
            case "TIME_LIMIT" -> "EXPIRED";
            default -> "LIMIT_EXHAUSTED";
        };
    }

    private ToolOutcome notRun(String invocationId, com.brianmehrman.harness.model.ToolRequest request) {
        return new ToolOutcome(invocationId, request.providerCallId(), request.name(), new SnapshotRef(snapshot),
                "{\"status\":\"ERROR\",\"code\":\"BUDGET_PAUSED\",\"detail\":\"Not run: the run paused at its budget. " +
                        "Repeat this call after the run resumes.\"}", false, false);
    }

    private void event(String type, Map<String, Object> detail) {
        sequence++;
        activities.publishEvent(spec.runId(), sequence, version, status, snapshot, type, detail);
    }

    @Override public CommandResult resume(ResumeCommand command) {
        return control(command.commandId(), command.expectedVersion(), command, () -> {
            // A resume must extend the limit that was reached; STALLED accepts any resume.
            if (resumesApplied >= policy.maxResumes() ||
                    "TURN_LIMIT".equals(pauseReason) && command.extraTurns() <= 0 ||
                    "TOOL_LIMIT".equals(pauseReason) && command.extraToolCalls() <= 0 ||
                    "TIME_LIMIT".equals(pauseReason) && command.extraMinutes() <= 0 || command.extraTurns() > policy.resumeTurns() ||
                    !canExtendCost(command.extraCostMicros()) ||
                    "COST_LIMIT".equals(pauseReason) && (command.extraCostMicros() <= 0 ||
                            !canResumeCost(command.extraCostMicros())) ||
                    command.extraToolCalls() > policy.resumeToolCalls() || command.extraMinutes() > policy.resumeMinutes() ||
                    extraTurnsApplied + command.extraTurns() > policy.totalExtraTurns() ||
                    extraToolCallsApplied + command.extraToolCalls() > policy.totalExtraToolCalls() ||
                    extraMinutesApplied + command.extraMinutes() > policy.totalExtraMinutes())
                return "REJECTED_LIMIT";
            resumesApplied++;
            extraTurnsApplied += command.extraTurns();
            extraToolCallsApplied += command.extraToolCalls();
            extraMinutesApplied += command.extraMinutes();
            resumeRequest = command;
            return "APPLIED";
        });
    }

    private boolean canExtendCost(long extraCostMicros) {
        if (limitMicros == null) return true;
        try { Math.addExact(limitMicros, extraCostMicros); return true; }
        catch (ArithmeticException overflow) { return false; }
    }

    private boolean canResumeCost(long extraCostMicros) {
        if (costTerms == null || limitMicros == null) return false;
        long nextLimit;
        try { nextLimit = Math.addExact(limitMicros, extraCostMicros); }
        catch (ArithmeticException overflow) { return false; }
        long effective = costTerms.effectiveLimit(nextLimit);
        return spentMicros <= effective && costTerms.worstCase().micros() <= effective - spentMicros;
    }

    @Override public CommandResult stop(StopCommand command) {
        return control(command.commandId(), command.expectedVersion(), command, () -> {
            stopRequested = true;
            return "APPLIED";
        });
    }

    private CommandResult control(String commandId, long expectedVersion, Object payload,
            java.util.function.Supplier<String> apply) {
        var prior = controls.get(commandId);
        if (prior != null) return prior.payload().equals(payload) ? prior.result()
                : new CommandResult(spec.runId(), commandId, "REJECTED_CONFLICT");
        if (RunStatus.isTerminal(status)) return new CommandResult(spec.runId(), commandId, "REJECTED_TERMINAL");
        if (!paused || resumeRequest != null || stopRequested || cancelRequested)
            return new CommandResult(spec.runId(), commandId, "REJECTED_STATE");
        if (expectedVersion != version) return new CommandResult(spec.runId(), commandId, "REJECTED_VERSION");
        var result = new CommandResult(spec.runId(), commandId, apply.get());
        controls.put(commandId, new AcceptedControl(payload, result));
        return result;
    }

    private record AcceptedCancel(long expectedVersion, CommandResult result) {}
}
