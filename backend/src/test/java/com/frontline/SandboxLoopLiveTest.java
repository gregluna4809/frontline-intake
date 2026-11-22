package com.frontline;

import com.frontline.sandbox.SandboxLoop;
import com.frontline.sandbox.SandboxService;
import com.frontline.simulator.SimulatorEngine;
import com.frontline.simulator.SimulatorProperties;
import com.frontline.simulator.SimulatorRepository;
import com.frontline.simulator.TrafficSimulator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@Import(AuthenticatedApiTestConfiguration.class)
@Testcontainers
class SandboxLoopLiveTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired ApplicationContext context;
    @Autowired SandboxService sandbox;
    @Autowired TrafficSimulator simulator;
    @Autowired SimulatorEngine engine;
    @Autowired SimulatorProperties settings;
    @Autowired SimulatorRepository repository;
    @Autowired MockMvc mvc;

    @Test void liveModeKeepsItsTargetAndStopBehaviorAndHasNoSandboxLoopOrSummary() throws Exception {
        assertThat(context.getBeansOfType(SandboxLoop.class)).isEmpty();
        simulator.stop();
        sandbox.reconcile();
        assertThat(simulator.status().running()).isFalse();
        LocalDate date = LocalDate.parse("2026-11-30");
        repository.setSwitch("ARRIVALS", false);
        engine.tick(date, Instant.parse("2026-11-30T13:30:00Z"));
        var day = repository.day(date).orElseThrow();
        assertThat(day.targetArrivals()).isEqualTo(settings.rangeFor(date).draw(new Random(day.seed())));
        simulator.start();
        mvc.perform(post("/api/simulator/stop")).andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false));
        mvc.perform(get("/api/sandbox/summary").header("Authorization", "")).andExpect(status().isNotFound());
    }
}
