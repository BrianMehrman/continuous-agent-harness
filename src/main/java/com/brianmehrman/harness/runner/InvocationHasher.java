package com.brianmehrman.harness.runner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class InvocationHasher {
    private InvocationHasher() {}
    public static String hash(InvocationRequest request, String image) {
        if (request == null || request.snapshot() == null || request.operation() == null
                || request.operation() == Operation.EVALUATE || request.deadlineEpochMillis() <= 0) {
            throw new IllegalArgumentException("BUILD or TEST with a snapshot and deadline is required");
        }
        identity(request.runId()); identity(request.invocationId());
        if (image == null || !image.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Runner image must be an immutable local sha256 image ID");
        }
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (String value : new String[]{"runner-v1", request.runId(), request.invocationId(),
                    request.snapshot().sha256(), request.operation().name(), image}) {
                byte[] field = value.getBytes(StandardCharsets.UTF_8);
                out.writeInt(field.length); out.write(field);
            }
            out.writeLong(request.deadlineEpochMillis());
            return digest(bytes.toByteArray());
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xd800 && c <= 0xdfff))) {
            throw new IllegalArgumentException("Invalid run or invocation identity");
        }
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
