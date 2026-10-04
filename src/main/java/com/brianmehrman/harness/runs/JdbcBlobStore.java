package com.brianmehrman.harness.runs;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBlobStore implements BlobStore {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final JdbcTemplate jdbc;

    public JdbcBlobStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public String put(String mediaType, byte[] bytes) {
        if (mediaType == null || mediaType.isBlank() || mediaType.length() > 200 ||
                mediaType.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid media type");
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Blob exceeds 16 MiB");
        String digest = sha256(bytes);
        jdbc.update("insert into artifact_blob(sha256,media_type,content,size_bytes) values (?,?,?,?) on conflict do nothing",
                digest, mediaType, bytes, bytes.length);
        return digest;
    }

    @Override public byte[] get(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid blob digest");
        var rows = jdbc.queryForList("select content from artifact_blob where sha256=?", byte[].class, digest);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown blob digest");
        byte[] bytes = rows.getFirst();
        if (!sha256(bytes).equals(digest)) throw new IllegalStateException("Blob integrity check failed");
        return bytes.clone();
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
