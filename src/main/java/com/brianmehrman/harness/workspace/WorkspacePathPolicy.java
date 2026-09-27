package com.brianmehrman.harness.workspace;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

public final class WorkspacePathPolicy {
    public static final int MAX_FILE_BYTES = 65_536;
    public static final int MAX_TOTAL_BYTES = 1_048_576;
    public static final int MAX_FILES = 64;
    private WorkspacePathPolicy() {}

    public static void validateWritePath(String path) {
        validatePath(path);
        boolean javaSource = (path.startsWith("src/main/java/") || path.startsWith("src/test/java/"))
                && path.endsWith(".java");
        if (!path.equals("README.md") && !javaSource) {
            throw new IllegalArgumentException("Path is not writable");
        }
    }

    static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 1024 || path.startsWith("/")
                || path.contains("\\") || path.contains(":") || path.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid relative path");
        }
        utf8(path);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Path must be normalized");
            }
        }
    }

    public static int validateSnapshot(Map<String, String> files) {
        if (files == null || files.size() > MAX_FILES) throw new IllegalArgumentException("Too many files");
        int total = 0;
        for (var entry : files.entrySet()) {
            validatePath(entry.getKey());
            String path = entry.getKey();
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                if (files.containsKey(path.substring(0, slash))) {
                    throw new IllegalArgumentException("A file cannot also be a directory");
                }
            }
            validateContentLength(entry.getValue());
            int bytes = utf8(entry.getValue()).length;
            if (bytes > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 64 KiB");
            total += bytes;
        }
        if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Snapshot exceeds 1 MiB");
        return total;
    }

    static void validateContentLength(String text) {
        if (text == null || text.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("File exceeds 64 KiB or is null");
    }

    static byte[] utf8(String text) {
        if (text == null || text.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid text content");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Content must be valid UTF-8", e);
        }
    }

    /** For trusted filesystem import/staging; never follow links in any path component. */
    public static void requireRegularFile(Path root, Path file) {
        Path base = root.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(base) || Files.isSymbolicLink(base)) {
            throw new IllegalArgumentException("File escapes workspace");
        }
        Path cursor = base;
        for (Path segment : base.relativize(target)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException("Symbolic links are forbidden");
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Only regular files are supported");
        }
    }
}
