package com.brianmehrman.harness.runner;
import java.util.Optional;
public interface Runner {
    void ensureStarted(InvocationRequest request);
    Optional<InvocationResult> result(String invocationId);
    void cancel(String invocationId);
    boolean stopConfirmed(String invocationId);
}
