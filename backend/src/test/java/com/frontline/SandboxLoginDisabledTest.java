package com.frontline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="business.scheduling.enabled=false") @AutoConfigureMockMvc @Testcontainers
class SandboxLoginDisabledTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired MockMvc mvc;
    @Test void loginIsNotFoundInLiveModeAndUnsetLinksAreNull() throws Exception {
        mvc.perform(post("/api/sandbox/login").contentType("application/json").content("{\"role\":\"CLERK\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/business-status")).andExpect(status().isOk()).andExpect(jsonPath("$.sandboxUrl").isEmpty())
                .andExpect(jsonPath("$.liveUrl").isEmpty());
    }
}
