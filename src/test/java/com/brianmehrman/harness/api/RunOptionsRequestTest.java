package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.execution.ResumeCommand;
import com.brianmehrman.harness.runs.RepositoryWorkService;
import com.brianmehrman.harness.runs.StartCommand;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RunOptionsRequestTest {
    private static final Set<String> REQUIRED = Set.of("commandId");
    private static final Set<String> OPTIONAL = Set.of("profileRevision", "role", "allowRemote",
            "costLimitMicros", "costBufferPercent");

    @Test void acceptsOmittedProfileAndTypedTerms() {
        Map<String, Object> data = RunController.object("""
                {"commandId":"one","role":"developer","allowRemote":true,
                 "costLimitMicros":100000,"costBufferPercent":10}
                """, REQUIRED, OPTIONAL);
        assertNull(RunController.optionalString(data, "profileRevision"));
        assertEquals("developer", RunController.optionalString(data, "role"));
        assertEquals(true, RunController.optionalBoolean(data, "allowRemote"));
        assertEquals(100000L, RunController.optionalLong(data, "costLimitMicros"));
        assertEquals(10, RunController.optionalInteger(data, "costBufferPercent"));
    }

    @Test void rejectsInvalidTermTypesAndUnknownKeys() {
        assertThrows(RunController.BadInput.class,
                () -> RunController.optionalBoolean(Map.of("allowRemote", "true"), "allowRemote"));
        assertThrows(RunController.BadInput.class,
                () -> RunController.optionalLong(Map.of("costLimitMicros", 2.5), "costLimitMicros"));
        assertThrows(RunController.BadInput.class,
                () -> RunController.optionalInteger(Map.of("costBufferPercent", 2L), "costBufferPercent"));
        assertThrows(RunController.BadInput.class,
                () -> RunController.object("{\"commandId\":\"one\",\"extra\":1}", REQUIRED, OPTIONAL));
    }

    @Test void legacyCommandPayloadsKeepTheirOriginalShape() {
        var json = JsonMapper.builder().build();
        String start = json.writeValueAsString(new StartCommand("one", "task-tracker-v1", "local",
                Limits.codingDefaults()));
        assertFalse(start.contains("\"role\""));
        assertFalse(start.contains("\"allowRemote\""));
        String work = json.writeValueAsString(new RepositoryWorkService.CreateWorkRequest(
                "one", "title", "instructions", "local"));
        assertEquals("{\"commandId\":\"one\",\"title\":\"title\",\"instructions\":\"instructions\",\"profileRevision\":\"local\"}", work);
        String resume = json.writeValueAsString(new ResumeCommand("resume-one", 1, 2, 0, 0, null));
        assertFalse(resume.contains("extraCostMicros"), "A retry of a pre-M2 resume keeps its input digest");
    }
}
