package com.brianmehrman.harness.execution;

/** Paths that must never be exposed to model tools, regardless of role or profile. */
public final class ModelPathPolicy {
    private ModelPathPolicy() {}

    public static boolean denied(String path) {
        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (segment.equals(".secrets")) return true;
        }
        String filename = segments[segments.length - 1];
        return filename.equals(".env") || filename.startsWith(".env.") ||
                filename.endsWith(".pem") || filename.endsWith(".key") || filename.endsWith(".p12") ||
                filename.startsWith("id_rsa") || filename.startsWith("id_ed25519");
    }
}
