package com.brianmehrman.harness.model;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic model boundary for workflow and crash recovery tests. */
public final class ScriptedModelAdapter implements ModelAdapter {
    private final List<ModelReply> replies;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Barrier barrier;

    public ScriptedModelAdapter(List<ModelReply> replies) {
        this.replies = List.copyOf(replies);
    }

    public int callCount() { return calls.get(); }

    public void barrierOnCall(int callNumber, CountDownLatch entered, CountDownLatch release) {
        if (callNumber < 1) throw new IllegalArgumentException("Call number must be positive");
        barrier = new Barrier(callNumber, Objects.requireNonNull(entered), Objects.requireNonNull(release));
    }

    @Override public ModelReply call(ModelCall request) {
        Objects.requireNonNull(request);
        int number = calls.incrementAndGet();
        if (number > replies.size()) throw new IllegalStateException("SCRIPTED_REPLIES_EXHAUSTED");
        Barrier current = barrier;
        if (current != null && current.callNumber == number) {
            current.entered.countDown();
            try {
                long remaining = request.deadlineEpochMillis() - System.currentTimeMillis();
                if (remaining <= 0 || !current.release.await(remaining, TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SCRIPTED_CALL_INTERRUPTED", e);
            }
        }
        return replies.get(number - 1);
    }

    private record Barrier(int callNumber, CountDownLatch entered, CountDownLatch release) {}
}
