package com.brianmehrman.harness.config;

import com.brianmehrman.harness.HarnessApplication;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = HarnessApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("worker")
class InfrastructureIT {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired(required = false) WorkflowClient client;
    @Autowired(required = false) WorkflowServiceStubs temporal;

    @Test
    void profileRevisionSurvivesASeparateDatabaseConnection() throws Exception {
        String id = "it-" + UUID.randomUUID();
        String digest = UUID.randomUUID().toString();
        assertThat(jdbc.queryForObject("select description from flyway_schema_history where version='1' and success", String.class))
                .isEqualTo("create model profiles");
        jdbc.update("insert into model_profile_revision (id,profile_json,sha256,created_at) values (?,?::jsonb,?,current_timestamp)",
                id, "{\"adapter\":\"scripted\",\"credentialReference\":\"test-only\"}", digest);
        try (var connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            String user = connection.getMetaData().getUserName();
            String password = environment.getRequiredProperty("spring.datasource.password");
            try (var independent = DriverManager.getConnection(url, user, password);
                 var statement = independent.prepareStatement("select profile_json->>'adapter' from model_profile_revision where id=?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("scripted");
                }
            }
        } finally {
            jdbc.update("delete from model_profile_revision where id=?", id);
        }
    }

    @Test
    void applicationRoleCannotConnectToTemporalDatabases() {
        assertThat(jdbc.queryForObject(
                "select has_database_privilege(current_user, 'temporal', 'CONNECT')", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "select has_database_privilege(current_user, 'temporal_visibility', 'CONNECT')", Boolean.class))
                .isFalse();
    }

    @Test
    void configuredClientRoundTripsAPayloadThroughTheRealTemporalService() {
        assertThat(client).as("worker role must configure a Temporal client").isNotNull();
        assertThat(temporal).isNotNull();
        assertThat(temporal.blockingStub().withDeadlineAfter(10, TimeUnit.SECONDS)
                .describeNamespace(DescribeNamespaceRequest.newBuilder()
                        .setNamespace(client.getOptions().getNamespace()).build())
                .getNamespaceInfo().getName()).isEqualTo(client.getOptions().getNamespace());
        String id = UUID.randomUUID().toString();
        WorkerFactory factory = WorkerFactory.newInstance(client);
        try {
            factory.newWorker("infrastructure-" + id).registerWorkflowImplementationTypes(ProbeWorkflowImpl.class);
            factory.start();
            ProbeWorkflow workflow = client.newWorkflowStub(ProbeWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("infrastructure-" + id)
                            .setTaskQueue("infrastructure-" + id)
                            .setWorkflowExecutionTimeout(Duration.ofSeconds(20)).build());
            assertThat(workflow.echo(new Probe("payload-through-service", 21)))
                    .isEqualTo(new Probe("payload-through-service", 21));
        } finally {
            factory.shutdownNow();
            factory.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    public record Probe(String value, int schemaVersion) {}

    @WorkflowInterface
    public interface ProbeWorkflow { @WorkflowMethod Probe echo(Probe probe); }

    public static class ProbeWorkflowImpl implements ProbeWorkflow {
        @Override public Probe echo(Probe probe) { return probe; }
    }
}
