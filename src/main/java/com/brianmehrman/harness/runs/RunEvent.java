package com.brianmehrman.harness.runs;

public record RunEvent(String runId, long sequence, String eventId, String type, String payloadBlobId) {
    public RunEvent {
        if (runId == null || runId.isBlank() || sequence < 1 || eventId == null || eventId.isBlank() ||
                eventId.length() > 200 || eventId.codePoints().anyMatch(Character::isISOControl) ||
                type == null || !type.matches("[A-Z][A-Z_]{0,63}") ||
                payloadBlobId == null || !payloadBlobId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid run event");
    }
}
