package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.runs.WorkflowGateway;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases a stale benchmark slot only when Temporal confirms completion. */
@Component
@Profile("worker")
public class RunAdmissionReconciler {
    private final RunAdmissionStore admissions;
    private final WorkflowGateway workflows;

    public RunAdmissionReconciler(RunAdmissionStore admissions, WorkflowGateway workflows) {
        this.admissions = admissions;
        this.workflows = workflows;
    }

    @Scheduled(fixedDelay = 5000)
    public void reconcile() {
        try {
            admissions.reconcileCompleted(workflows);
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass()).warn("Admission reconciliation deferred: {}",
                    unavailable.getClass().getSimpleName());
        }
    }
}
