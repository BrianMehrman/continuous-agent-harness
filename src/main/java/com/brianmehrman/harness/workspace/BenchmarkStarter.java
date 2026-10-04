package com.brianmehrman.harness.workspace;

import java.io.IOException;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class BenchmarkStarter {
    static final String VERSION = "task-tracker-v1";
    private static final List<String> FILES = List.of("build.gradle", "settings.gradle", "gradle.lockfile",
            "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.properties",
            "gradle/wrapper/gradle-wrapper.jar.sha256", "gradle/verification-metadata.xml",
            "REQUIREMENTS.md", "src/main/java/example/tasktracker/TaskTracker.java");

    public SortedMap<String, String> files(String version) {
        if (!VERSION.equals(version)) throw new IllegalArgumentException("Unknown benchmark version");
        var files = new TreeMap<String, String>();
        for (String path : FILES) {
            try (var stream = new ClassPathResource("benchmarks/" + VERSION + "/starter/" + path).getInputStream()) {
                byte[] bytes = stream.readNBytes(WorkspacePathPolicy.MAX_FILE_BYTES + 1);
                if (bytes.length > WorkspacePathPolicy.MAX_FILE_BYTES) throw new IllegalStateException("Starter file is oversized");
                String content = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                files.put(path, content);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read trusted starter asset: " + path, e);
            }
        }
        try (var stream = new ClassPathResource("benchmarks/" + VERSION + "/starter/gradle/wrapper/gradle-wrapper.jar").getInputStream()) {
            byte[] bytes = stream.readNBytes(WorkspacePathPolicy.MAX_TOTAL_BYTES + 1);
            if (bytes.length > WorkspacePathPolicy.MAX_TOTAL_BYTES || !SnapshotHasher.binary(bytes)
                    .equals(files.get("gradle/wrapper/gradle-wrapper.jar.sha256").strip())) {
                throw new IllegalStateException("Trusted wrapper checksum mismatch");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read trusted wrapper binary", e);
        }
        WorkspacePathPolicy.validateSnapshot(files);
        return files;
    }
}
