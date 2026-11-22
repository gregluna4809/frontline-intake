package com.frontline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"frontline.sandbox.enabled=true", "business.scheduling.enabled=false", "frontline.live.url=https://live.example", "frontline.sandbox.url=https://sandbox.example"})
@AutoConfigureMockMvc @Testcontainers
class SandboxLoginTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest @ValueSource(strings={"CLERK","LSR","SUPERVISING_LSR","CENTER_MANAGER"})
    void anonymousLoginSelectsLowestActiveStaffForRole(String role) throws Exception {
        long id = jdbc.queryForObject("SELECT MIN(id) FROM staff WHERE active AND role = ?", Long.class, role);
        mvc.perform(post("/api/sandbox/login").contentType("application/json").content("{\"role\":\""+role+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role))
                .andExpect(jsonPath("$.staffId").value(id)).andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.expiresIn").value(28800));
    }
    @Test void administratorIsRejectedAndPublicStatusExposesLinks() throws Exception {
        mvc.perform(post("/api/sandbox/login").contentType("application/json").content("{\"role\":\"ADMINISTRATOR\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/business-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sandboxUrl").value("https://sandbox.example"))
                .andExpect(jsonPath("$.liveUrl").value("https://live.example"));
    }
}
