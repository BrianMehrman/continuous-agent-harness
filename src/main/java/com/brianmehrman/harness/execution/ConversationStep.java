package com.brianmehrman.harness.execution;

/** A folded conversation plus the stall facts the workflow acts on. */
public record ConversationStep(String blobId, String nudged, WorkingMemory.StallVerdict stall, String note) {}
