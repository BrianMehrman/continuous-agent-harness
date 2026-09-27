package com.brianmehrman.harness.workspace;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public final class SnapshotHasher {
    private SnapshotHasher() {}
    public static String content(String content) {
        return binary(WorkspacePathPolicy.utf8(content));
    }
    static String binary(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }
    public static String snapshot(Map<String, String> files) {
        MessageDigest hash = digest();
        field(hash, "workspace-snapshot-v1");
        hash.update(ByteBuffer.allocate(4).putInt(files.size()).array());
        new TreeMap<>(files).forEach((path, content) -> { field(hash, path); field(hash, content); });
        return HexFormat.of().formatHex(hash.digest());
    }
    static String request(WriteRequest request) {
        MessageDigest hash = digest();
        for (String value : new String[]{"workspace-write-v1", request.runId(), request.invocationId(),
                request.parent().sha256(), request.path(), request.expectedSha256(), request.content()}) field(hash, value);
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void field(MessageDigest hash, String value) {
        byte[] bytes = WorkspacePathPolicy.utf8(value);
        hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        hash.update(bytes);
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
