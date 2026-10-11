package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.brianmehrman.harness.config.ModelConfiguration;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProfileControllerTest {
    @Test void unsupportedLocalModelIsAClientError() {
        var profiles = mock(ProfileRevisionStore.class);
        var models = mock(ModelConfiguration.class);
        when(models.selectLocalProfile(any(), eq("uninstalled")))
                .thenThrow(new IllegalStateException("LOCAL_WEIGHTS_UNVERIFIED"));
        var controller = new ProfileController(mock(JdbcTemplate.class), profiles, models);
        assertThrows(RunController.BadInput.class,
                () -> controller.probe("{\"modelTag\":\"uninstalled\"}"));
    }
}
