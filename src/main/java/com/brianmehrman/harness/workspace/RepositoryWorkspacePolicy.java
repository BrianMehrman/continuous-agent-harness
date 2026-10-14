package com.brianmehrman.harness.workspace;

import java.util.Map;

/** Source limits and writable paths for frozen repository tasks. */
public final class RepositoryWorkspacePolicy {
    public static final int MAX_FILES = 512;
    public static final int MAX_FILE_BYTES = 262_144;
    public static final int MAX_TOTAL_BYTES = 8_388_608;
    private RepositoryWorkspacePolicy() {}

    public static boolean allowed(String path) {
        WorkspacePathPolicy.validatePath(path);
        // Host-side evaluator cases, seeds, and fixtures are private to the trusted benchmark.
        if (path.contains("/benchmark/fixtures/") || path.contains("/com/brianmehrman/harness/benchmark/")) return false;
        return path.equals("README.md") ||
                (path.startsWith("src/main/java/") || path.startsWith("src/test/java/")) && path.endsWith(".java") ||
                path.startsWith("src/main/resources/") && path.endsWith(".yaml") ||
                path.startsWith("src/main/resources/db/migration/") && path.endsWith(".sql") ||
                path.startsWith("web/src/") && (path.endsWith(".ts") || path.endsWith(".tsx") || path.endsWith(".css")) ||
                path.startsWith("scripts/") && path.endsWith(".py") ||
                path.startsWith("docs/") && path.endsWith(".md");
    }

    public static void validateWritePath(String path) {
        if (!allowed(path)) throw new IllegalArgumentException("Path is not writable");
    }

    public static int validateSnapshot(Map<String, String> files) {
        if (files == null || files.size() > MAX_FILES) throw new IllegalArgumentException("Too many files");
        int total = 0;
        for (var file : files.entrySet()) {
            validateWritePath(file.getKey());
            for (int slash = file.getKey().indexOf('/'); slash >= 0; slash = file.getKey().indexOf('/', slash + 1)) {
                if (files.containsKey(file.getKey().substring(0, slash)))
                    throw new IllegalArgumentException("A file cannot also be a directory");
            }
            int bytes = WorkspacePathPolicy.utf8(file.getValue()).length;
            if (bytes > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB");
            total += bytes;
            if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Snapshot exceeds 8 MiB");
        }
        return total;
    }
}
