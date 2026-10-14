package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.workspace.SnapshotHasher;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Imports a bounded set of tracked text files from a committed local Git tree. */
@Component
public class RepositorySourceImporter {
    private static final int MAX_FILES = 512;
    private static final int MAX_FILE_BYTES = 262_144;
    private static final int MAX_TOTAL_BYTES = 8_388_608;
    private final Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    public CapturedSource capture(String baseRef) {
        if (!"main".equals(baseRef)) throw new IllegalArgumentException("Only local main is supported");
        String commit = new String(git(100, "rev-parse", "--verify", "main^{commit}"), StandardCharsets.US_ASCII).trim();
        if (!commit.matches("[0-9a-f]{40}")) throw new IllegalStateException("Invalid local base commit");
        byte[] listing = git(1_048_576, "ls-tree", "-r", "-z", commit);
        SortedMap<String, String> files = new TreeMap<>();
        int total = 0;
        for (byte[] item : splitNul(listing)) {
            int tab = indexOf(item, (byte)'\t');
            if (tab < 0) throw new IllegalStateException("Invalid Git tree entry");
            String header = new String(item, 0, tab, StandardCharsets.US_ASCII);
            String path = decode(Arrays.copyOfRange(item, tab + 1, item.length));
            if (!allowed(path)) continue;
            String[] fields = header.split(" ");
            if (fields.length != 3 || !fields[0].equals("100644") && !fields[0].equals("100755") ||
                    !fields[1].equals("blob") || !fields[2].matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("Repository source must be a regular file");
            if (files.size() >= MAX_FILES) throw new IllegalArgumentException("Repository has too many source files");
            byte[] content = git(MAX_FILE_BYTES + 1, "cat-file", "blob", fields[2]);
            if (content.length > MAX_FILE_BYTES || total + content.length > MAX_TOTAL_BYTES)
                throw new IllegalArgumentException("Repository source limit exceeded");
            files.put(path, decode(content));
            total += content.length;
        }
        return new CapturedSource(commit, files, SnapshotHasher.snapshot(files), total);
    }

    private byte[] git(int maxBytes, String... arguments) {
        var command = new java.util.ArrayList<String>();
        command.add("git"); command.add("-C"); command.add(repository.toString());
        command.addAll(java.util.List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).start();
            byte[] output = process.getInputStream().readNBytes(maxBytes + 1);
            if (output.length > maxBytes) { process.destroyForcibly(); throw new IllegalArgumentException("Git output limit exceeded"); }
            byte[] error = process.getErrorStream().readNBytes(4096);
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new IllegalStateException("Local Git timed out");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Local Git unavailable: " +
                    new String(error, StandardCharsets.UTF_8).strip());
            return output;
        } catch (IOException e) { throw new IllegalStateException("Local Git unavailable", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Local Git interrupted", e); }
    }

    private static boolean allowed(String path) {
        if (path.contains("..") || path.contains("\\") || path.startsWith("/") || path.indexOf('\0') >= 0)
            return false;
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

    private static String decode(byte[] bytes) {
        if (indexOf(bytes, (byte)0) >= 0) throw new IllegalArgumentException("Binary repository source is unsupported");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Repository source is not UTF-8", e); }
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) if (bytes[i] == value) return i;
        return -1;
    }

    private static java.util.List<byte[]> splitNul(byte[] bytes) {
        var result = new java.util.ArrayList<byte[]>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) if (bytes[i] == 0) {
            result.add(Arrays.copyOfRange(bytes, start, i)); start = i + 1;
        }
        if (start != bytes.length) throw new IllegalStateException("Unterminated Git tree listing");
        return result;
    }

    public record CapturedSource(String commitSha, SortedMap<String, String> files,
                                 String snapshotSha256, int byteCount) {}
}
