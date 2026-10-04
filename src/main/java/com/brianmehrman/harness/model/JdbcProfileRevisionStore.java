package com.brianmehrman.harness.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcProfileRevisionStore implements ProfileRevisionStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcProfileRevisionStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public ProfileRevision save(ProfileRevision profile) {
        Objects.requireNonNull(profile);
        String serialized = json.writeValueAsString(profile);
        String digest = sha256(serialized);
        jdbc.update("insert into model_profile_revision(id,profile_json,sha256) values (?,?::jsonb,?) on conflict do nothing",
                profile.id(), serialized, digest);
        ProfileRevision stored = get(profile.id());
        if (!stored.equals(profile)) throw new IllegalArgumentException("Profile revision ID already has different settings");
        return stored;
    }

    @Override public ProfileRevision get(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Profile revision ID is required");
        var rows = jdbc.query("select profile_json::text,sha256 from model_profile_revision where id=?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown profile revision");
        ProfileRevision profile = json.readValue(rows.getFirst()[0], ProfileRevision.class);
        if (!profile.id().equals(id) || !sha256(json.writeValueAsString(profile)).equals(rows.getFirst()[1]))
            throw new IllegalStateException("Profile revision integrity check failed");
        return profile;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
