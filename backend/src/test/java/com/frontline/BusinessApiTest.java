package com.frontline;

import com.frontline.business.TicketSequenceService;
import com.frontline.model.Visit;
import com.frontline.simulator.SimulatorProperties;
import com.frontline.simulator.TrafficSimulator;
import com.frontline.visit.VisitService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class BusinessApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitService visits;
    @Autowired TicketSequenceService tickets;

    @BeforeEach
    void clearVisits() {
        jdbc.execute("TRUNCATE visit_event, visit RESTART IDENTITY");
        jdbc.execute("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 1");
        jdbc.execute("ALTER SEQUENCE appointment_ticket_sequence RESTART WITH 1");
        jdbc.execute("UPDATE resource SET status = 'FREE'");
        at("2026-09-14T16:00:00Z");
    }

    private void at(String instant) { when(clock.instant()).thenReturn(Instant.parse(instant)); }
    private Visit walkIn() { return visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null); }
    private Visit appointment() {
        Visit visit = visits.checkIn(Visit.VisitType.APPOINTMENT, null,
                Visit.AppointmentCategory.REEMPLOYMENT, Visit.AppointmentType.FOLLOWUP);
        visits.identify(visit.id(), 1, 0);
        return visits.route(visit.id(), 1).visit();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-08T12:29:00Z,false,2026-09-04,2026-09-04",
            "2026-09-08T12:30:00Z,true,2026-09-08,2026-09-04",
            "2026-09-08T20:45:00Z,false,2026-09-08,2026-09-08",
            "2026-09-13T16:00:00Z,false,2026-09-11,2026-09-11"
    })
    void businessStatusReflectsMockedTime(String instant, boolean open, String current, String completed) throws Exception {
        at(instant);
        mvc.perform(get("/api/business-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.isOpen").value(open))
                .andExpect(jsonPath("$.currentBusinessDate").value(current))
                .andExpect(jsonPath("$.lastCompletedBusinessDate").value(completed));
    }

    @Test
    void checkInAndSimulatorStampCalendarBusinessDate() throws Exception {
        at("2026-09-08T12:29:00Z");
        mvc.perform(post("/api/visits").contentType("application/json")
                        .content("{\"visitType\":\"WALK_IN\",\"reason\":\"PHONE_ACCESS\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.businessDate").value("2026-09-04"));
        at("2026-09-08T12:30:00Z");
        var simulator = new TrafficSimulator(visits, new SimulatorProperties(false, 1,
                Map.of("APPOINTMENT", 1), Map.of("REEMPLOYMENT", 1), Map.of("INITIAL", 1)));
        simulator.start();
        simulator.tick();
        Visit visit = visits.list(null).getFirst().visit();
        assertThat(visit.businessDate()).isEqualTo(LocalDate.parse("2026-09-08"));
        assertThat(visits.detail(visit.id()).events().getFirst().actor()).isEqualTo("SIMULATOR");
    }

    @Test
    void queuesDefaultToBusinessDateAndAllowExplicitHistory() throws Exception {
        Visit old = appointment();
        at("2026-09-15T16:00:00Z");
        Visit current = appointment();
        for (String path : List.of("/api/visits", "/api/visits?status=WAITING_STAFF")) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(current.id()));
            mvc.perform(get(path).param("businessDate", "2026-09-14")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(old.id()));
        }
        mvc.perform(get("/api/visits").param("businessDate", "2026-09-10")).andExpect(content().json("[]"));
        mvc.perform(get("/api/visits").param("businessDate", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/visits/{id}", old.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.businessDate").value("2026-09-14"));
        at("2026-09-16T12:29:00Z");
        mvc.perform(get("/api/visits")).andExpect(jsonPath("$[0].id").value(current.id()));
    }

    @Test
    void inventoryScopesVisitDetailsWithoutFreeingPriorDayResources() throws Exception {
        Visit old = walkIn();
        visits.identify(old.id(), 1, 0);
        visits.route(old.id(), 1);
        at("2026-09-15T16:00:00Z");
        Visit current = walkIn();
        visits.identify(current.id(), 2, 0);
        visits.route(current.id(), 1);
        mvc.perform(get("/api/resources")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].visitId")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].status")
                        .value(org.hamcrest.Matchers.contains("OCCUPIED")))
                .andExpect(jsonPath("$[?(@.label == 'Phone 02')].visitId")
                        .value(org.hamcrest.Matchers.contains((int) current.id())));
        mvc.perform(get("/api/resources").param("businessDate", "2026-09-14"))
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].visitId")
                        .value(org.hamcrest.Matchers.contains((int) old.id())))
                .andExpect(jsonPath("$[?(@.label == 'Phone 02')].visitId")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'OCCUPIED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rescheduledReplacementUsesNewBusinessDate() {
        Visit old = appointment();
        at("2026-09-15T16:00:00Z");
        var result = visits.reschedule(old.id(), old.version());
        assertThat(result.closedVisit().visit().businessDate()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(result.replacementVisit().visit().businessDate()).isEqualTo(LocalDate.parse("2026-09-15"));
        assertThat(result.replacementVisit().visit().previousVisitId()).isEqualTo(old.id());
        assertThat(visits.list(null)).hasSize(1);
    }

    @Test
    void openingResetsBothSequencesAndKeepsTicketUniquenessWithinEachDate() {
        assertThat(walkIn().ticket()).isEqualTo("B0001");
        assertThat(appointment().ticket()).isEqualTo("A0001");
        at("2026-09-15T12:30:00Z");
        tickets.resetForOpening();
        for (String sequence : List.of("visit_ticket_sequence", "appointment_ticket_sequence")) {
            assertThat(jdbc.queryForObject("SELECT last_value FROM " + sequence, Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT is_called FROM " + sequence, Boolean.class)).isFalse();
        }
        assertThat(walkIn().ticket()).isEqualTo("B0001");
        assertThat(appointment().ticket()).isEqualTo("A0001");
        tickets.resetForOpening();
        assertThat(walkIn().ticket()).isEqualTo("B0002");
        assertThat(appointment().ticket()).isEqualTo("A0002");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE ticket = 'B0001'", Integer.class)).isEqualTo(2);
        var current = visits.list(null).getLast().visit();
        assertThatThrownBy(() -> jdbc.update("UPDATE visit SET ticket = 'B0001' WHERE id = ?", current.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void openingResetIsSafeWhenRacingFirstArrival() throws Exception {
        walkIn();
        appointment();
        at("2026-09-15T12:30:00Z");
        try (var executor = Executors.newFixedThreadPool(3)) {
            var results = executor.invokeAll(List.<Callable<String>>of(
                    () -> { tickets.resetForOpening(); return "reset"; },
                    () -> walkIn().ticket(), () -> walkIn().ticket()));
            assertThat(List.of(results.get(0).get(), results.get(1).get(), results.get(2).get()))
                    .containsExactlyInAnyOrder("reset", "B0001", "B0002");
        }
    }

    @Test
    void closedDayDoesNotResetSequences() {
        walkIn();
        at("2026-09-19T16:00:00Z");
        tickets.resetForOpening();
        assertThat(jdbc.queryForObject("SELECT last_value FROM visit_ticket_sequence", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_called FROM visit_ticket_sequence", Boolean.class)).isTrue();
    }

    @Test
    void migrationBackfillsEasternCreatedDateAndPreservesExistingVisits() {
        String schema = "step8_upgrade";
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("6").load().migrate();
        jdbc.update("""
                INSERT INTO step8_upgrade.visit (ticket, visit_type, reason, status, created_at)
                VALUES ('B0001', 'WALK_IN', 'OTHER', 'CHECKED_IN', TIMESTAMPTZ '2026-09-08 01:00:00+00')
                """);
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(jdbc.queryForObject("SELECT business_date FROM step8_upgrade.visit", LocalDate.class))
                .isEqualTo(LocalDate.parse("2026-09-07"));
        assertThat(jdbc.queryForObject("SELECT ticket FROM step8_upgrade.visit", String.class)).isEqualTo("B0001");
        assertThatThrownBy(() -> jdbc.update("UPDATE step8_upgrade.visit SET business_date = NULL"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
