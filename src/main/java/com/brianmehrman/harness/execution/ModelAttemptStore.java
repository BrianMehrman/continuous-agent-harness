package com.brianmehrman.harness.execution;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable model-call ownership and response receipt; ambiguous redelivery consumes an attempt. */
@Repository
public class ModelAttemptStore {
    public enum Outcome { STARTED, RECORDED, UNKNOWN }
    public record AttemptLease(String runId, String invocationId, int attempt, Outcome outcome,
            String ownerToken, String responseBlobId) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ModelAttemptStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(10);
    }

    public AttemptLease begin(String runId, String invocationId, int attempt,
            String inputSha256, String requestBlobId) {
        if (runId == null || runId.isBlank() || invocationId == null || invocationId.isBlank() ||
                attempt < 1 || attempt > 2 || inputSha256 == null || !inputSha256.matches("[0-9a-f]{64}") ||
                requestBlobId == null || !requestBlobId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid model attempt identity");
        return transactions.execute(status -> {
            String token = UUID.randomUUID().toString();
            int inserted = jdbc.update("insert into model_attempt(run_id,invocation_id,attempt,input_sha256," +
                            "status,owner_token,request_blob_id) values (?,?,?,?,'IN_FLIGHT',?,?) " +
                            "on conflict do nothing",
                    runId, invocationId, attempt, inputSha256, token, requestBlobId);
            if (inserted == 1) return new AttemptLease(runId, invocationId, attempt, Outcome.STARTED, token, null);
            var rows = jdbc.query("select input_sha256,request_blob_id,status,response_blob_id " +
                            "from model_attempt where run_id=? and invocation_id=? and attempt=? for update",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                    runId, invocationId, attempt);
            if (rows.isEmpty()) throw new IllegalStateException("Model attempt disappeared");
            String[] row = rows.getFirst();
            if (!inputSha256.equals(row[0]) || !requestBlobId.equals(row[1]))
                throw new IllegalArgumentException("Model attempt identity reused with different input");
            if ("RECORDED".equals(row[2]))
                return new AttemptLease(runId, invocationId, attempt, Outcome.RECORDED, null, row[3]);
            if ("IN_FLIGHT".equals(row[2]))
                jdbc.update("update model_attempt set status='UNKNOWN',owner_token=null,ended_at=now() " +
                                "where run_id=? and invocation_id=? and attempt=?",
                        runId, invocationId, attempt);
            return new AttemptLease(runId, invocationId, attempt, Outcome.UNKNOWN, null, null);
        });
    }

    public boolean complete(AttemptLease lease, String responseBlobId) {
        Objects.requireNonNull(lease);
        if (lease.outcome() != Outcome.STARTED || lease.ownerToken() == null ||
                responseBlobId == null || !responseBlobId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid model receipt");
        return transactions.execute(status -> jdbc.update("update model_attempt set status='RECORDED'," +
                        "owner_token=null,response_blob_id=?,ended_at=now() where run_id=? and invocation_id=? " +
                        "and attempt=? and status='IN_FLIGHT' and owner_token=?",
                responseBlobId, lease.runId(), lease.invocationId(), lease.attempt(), lease.ownerToken()) == 1);
    }

    public boolean abandon(AttemptLease lease) {
        Objects.requireNonNull(lease);
        if (lease.outcome() != Outcome.STARTED || lease.ownerToken() == null)
            throw new IllegalArgumentException("Only an owned attempt can be abandoned");
        return transactions.execute(status -> jdbc.update("update model_attempt set status='UNKNOWN'," +
                        "owner_token=null,ended_at=now() where run_id=? and invocation_id=? and attempt=? " +
                        "and status='IN_FLIGHT' and owner_token=?",
                lease.runId(), lease.invocationId(), lease.attempt(), lease.ownerToken()) == 1);
    }
}
