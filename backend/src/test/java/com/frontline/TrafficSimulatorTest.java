package com.frontline;
import com.frontline.model.Visit;
import com.frontline.simulator.*;
import com.frontline.visit.VisitService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
class TrafficSimulatorTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired VisitService visits;
    @Autowired TrafficSimulator configured;
    @Autowired SimulatorProperties properties;

    @ParameterizedTest
    @CsvSource({"PHONE_ACCESS,REEMPLOYMENT", "COMPUTER_ACCESS,REEMPLOYMENT", "JOB_SEARCH,REEMPLOYMENT",
        "DOCUMENT_SERVICES,REEMPLOYMENT", "BENEFITS_ASSISTANCE,REEMPLOYMENT", "OTHER,REEMPLOYMENT",
        "APPOINTMENT,REEMPLOYMENT", "APPOINTMENT,CAREER_SERVICES", "APPOINTMENT,VETERANS"})
    void tickCreatesValidVisitsWithSimulatorActor(String choice, String category) {
        var simulator = new TrafficSimulator(visits, new SimulatorProperties(false, 2,
                Map.of(choice, 1), Map.of(category, 1)));
        var before = visits.list(null).stream().map(item -> item.visit().id()).collect(java.util.stream.Collectors.toSet());
        simulator.tick();
        assertThat(simulator.status().createdSinceStart()).isZero();
        simulator.start();
        simulator.tick();
        var created = visits.list(null).stream().filter(item -> !before.contains(item.visit().id())).toList();
        assertThat(created).hasSize(2);
        for (var item : created) {
            Visit visit = item.visit();
            assertThat(visit.status()).isEqualTo(Visit.Status.CHECKED_IN);
            if (choice.equals("APPOINTMENT")) {
                assertThat(visit.visitType()).isEqualTo(Visit.VisitType.APPOINTMENT);
                assertThat(visit.reason()).isNull();
                assertThat(visit.appointmentCategory()).isEqualTo(category);
            } else {
                assertThat(visit.visitType()).isEqualTo(Visit.VisitType.WALK_IN);
                assertThat(visit.reason()).isEqualTo(Visit.Reason.valueOf(choice));
                assertThat(visit.appointmentCategory()).isNull();
            }
            assertThat(visits.detail(visit.id()).events()).singleElement()
                .satisfies(event -> assertThat(event.actor()).isEqualTo("SIMULATOR"));
        }
        assertThat(simulator.status().createdSinceStart()).isEqualTo(2);
        simulator.start();
        assertThat(simulator.status().createdSinceStart()).isEqualTo(2);
        simulator.stop();
        simulator.tick();
        assertThat(simulator.status().createdSinceStart()).isEqualTo(2);
        simulator.start();
        assertThat(simulator.status().createdSinceStart()).isZero();
    }

    @Test
    void endpointsToggleStatusAndDefaultIsStopped() throws Exception {
        assertThat(configured.status().running()).isFalse();
        assertThat(properties.arrivalsPerMinute()).isEqualTo(4);
        var simulator = new TrafficSimulator(visits, properties);
        var mvc = MockMvcBuilders.standaloneSetup(new SimulatorController(simulator)).build();
        mvc.perform(get("/api/simulator/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.running").value(false)).andExpect(jsonPath("$.arrivalsPerMinute").value(4))
            .andExpect(jsonPath("$.createdSinceStart").value(0));
        mvc.perform(post("/api/simulator/start")).andExpect(status().isOk()).andExpect(jsonPath("$.running").value(true));
        mvc.perform(post("/api/simulator/stop")).andExpect(status().isOk()).andExpect(jsonPath("$.running").value(false));
    }

    @Test
    void enabledConfigurationStartsRunningAndInvalidWeightsAreRejected() {
        assertThat(new TrafficSimulator(visits, new SimulatorProperties(true, 1,
                Map.of("OTHER", 1), Map.of("VETERANS", 1))).status().running()).isTrue();
        assertThatThrownBy(() -> new SimulatorProperties(false, 1, Map.of("OTHER", 0), Map.of("VETERANS", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulatorProperties(false, -1, Map.of("OTHER", 1), Map.of("VETERANS", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
