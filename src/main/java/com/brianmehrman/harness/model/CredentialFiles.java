package com.brianmehrman.harness.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Reads model credentials on the host at dispatch time. Errors never include file contents. */
public final class CredentialFiles {
    private final Path directory;

    public CredentialFiles(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public String read(String reference) {
        if (reference == null || !reference.matches("[a-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid credential reference");
        Path file = directory.resolve(reference);
        try {
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file) ||
                    !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw unavailable(reference);
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_READ) ||
                    permissions.contains(PosixFilePermission.GROUP_WRITE) ||
                    permissions.contains(PosixFilePermission.GROUP_EXECUTE) ||
                    permissions.contains(PosixFilePermission.OTHERS_READ) ||
                    permissions.contains(PosixFilePermission.OTHERS_WRITE) ||
                    permissions.contains(PosixFilePermission.OTHERS_EXECUTE))
                throw unavailable(reference);
            String key = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (key.isBlank()) throw unavailable(reference);
            return key;
        } catch (IOException | SecurityException error) {
            throw unavailable(reference);
        }
    }

    private static IllegalStateException unavailable(String reference) {
        return new IllegalStateException("CREDENTIAL_UNAVAILABLE: " + reference);
    }
}
