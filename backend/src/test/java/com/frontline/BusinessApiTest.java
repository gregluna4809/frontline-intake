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
@org.springframework.security.test.context.support.WithMockUser(username = "staff1", roles = "LSR")
@Testcontainers
class BusinessApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitService visits;
    @Autowired TicketSequenceService tickets;
    @Autowired com.frontline.business.BusinessDayService days;
    @Autowired com.frontline.coverage.CoverageService coverage;

    @BeforeEach
    void clearVisits() {
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM visit_event");
        jdbc.execute("DELETE FROM visit");
        jdbc.execute("ALTER TABLE visit ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE visit_event ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("TRUNCATE call_out, business_day_snapshot, business_day_rollover RESTART IDENTITY");
        jdbc.execute("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 1");
        jdbc.execute("ALTER SEQUENCE appointment_ticket_sequence RESTART WITH 1");
        jdbc.execute("UPDATE resource SET status = 'FREE'");
        at("2026-09-14T16:00:00Z");
    }

    private void at(String instant) { when(clock.instant()).thenReturn(Instant.parse(instant)); }
    private Visit walkIn() { return visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null); }
    private Visit appointment() {
        Visit visit = visits.checkIn(Visit.VisitType.APPOINTMENT, null,
                Visit.AppointmentCategory.MANDATORY);
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
                Map.of("APPOINTMENT", 1), Map.of("MANDATORY", 1)));
        simulator.start();
        simulator.tick();
        Visit visit = visits.list(null).getFirst().visit();
        assertThat(visit.businessDate()).isEqualTo(LocalDate.parse("2026-09-08"));
        assertThat(visits.detail(visit.id()).events().getFirst().actor()).isEqualTo("SIMULATOR");
    }

    @Test
    void queuesDefaultToBusinessDateAndAllowExplicitHistory() throws Exception {
        Visit old = appointment();
        jdbc.update("UPDATE visit SET appointment_status = 'RESCHEDULED' WHERE id = ?", old.id());
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
    void inventoryShowsReusedResourceForTheNewBusinessDay() throws Exception {
        Visit old = walkIn();
        visits.identify(old.id(), 1, 0);
        visits.route(old.id(), 1);
        at("2026-09-15T16:00:00Z");
        Visit current = walkIn();
        visits.identify(current.id(), 2, 0);
        visits.route(current.id(), 1);
        mvc.perform(get("/api/resources")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.label == 'Phone 01')].visitId")
                        .value(org.hamcrest.Matchers.contains((int) current.id())));
        assertThat(visits.detail(old.id()).visit().status()).isEqualTo(Visit.Status.AT_RESOURCE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status = 'OCCUPIED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rescheduledReplacementUsesNewBusinessDate() {
        Visit old = appointment();
        jdbc.update("UPDATE visit SET appointment_status = 'RESCHEDULED' WHERE id = ?", old.id());
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
                .schemas(schema).defaultSchema(schema).target("7").load().migrate();
        assertThat(jdbc.queryForObject("SELECT business_date FROM step8_upgrade.visit", LocalDate.class))
                .isEqualTo(LocalDate.parse("2026-09-07"));
        assertThat(jdbc.queryForObject("SELECT ticket FROM step8_upgrade.visit", String.class)).isEqualTo("B0001");
        assertThatThrownBy(() -> jdbc.update("UPDATE step8_upgrade.visit SET business_date = NULL"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private Visit occupied() {
        Visit visit = walkIn();
        visits.identify(visit.id(), 1, 0);
        return visits.route(visit.id(), 1).visit();
    }

    @Test
    void openingFreesEveryResourceWithoutChangingOldVisitAndRunsOnlyOnce() {
        Visit old = occupied();
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'COMPUTER'");
        var events = visits.detail(old.id()).events();
        at("2026-09-15T12:29:00Z");
        tickets.resetForOpening();
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, old.resourceId()))
                .isEqualTo("OCCUPIED");
        at("2026-09-15T12:30:00Z");
        tickets.resetForOpening();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status <> 'FREE'", Integer.class)).isZero();
        assertThat(visits.detail(old.id()).visit()).isEqualTo(old);
        assertThat(visits.detail(old.id()).events()).isEqualTo(events);
        Visit current = occupied();
        assertThat(current.resourceId()).isEqualTo(old.resourceId());
        tickets.resetForOpening();
        assertThat(jdbc.queryForObject("SELECT active_visit_id FROM resource WHERE id = ?", Long.class, current.resourceId()))
                .isEqualTo(current.id());
        visits.close(old.id(), old.version());
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, current.resourceId()))
                .isEqualTo("OCCUPIED");
        visits.close(current.id(), current.version());
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, current.resourceId()))
                .isEqualTo("FREE");
    }

    @Test
    void snapshotDefaultsToCompletedDayAndRemainsFrozenAcrossMutationsAndRollover() throws Exception {
        Visit old = occupied();
        Visit completed = appointment();
        visits.close(completed.id(), completed.version());
        coverage.callOut(2, LocalDate.parse("2026-09-14"), "Synthetic coverage note");
        at("2026-09-14T20:45:00Z");
        String snapshot = mvc.perform(get("/api/snapshot")).andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-09-14"))
                .andExpect(jsonPath("$.visits.length()").value(2))
                .andExpect(jsonPath("$.visits[0].status").value("AT_RESOURCE"))
                .andExpect(jsonPath("$.visits[1].status").value("CLOSED"))
                .andExpect(jsonPath("$.resources[?(@.label == 'Phone 01')].status")
                        .value(org.hamcrest.Matchers.contains("OCCUPIED")))
                .andExpect(jsonPath("$.staff.length()").value(10))
                .andExpect(jsonPath("$.coverage[?(@.staffId == 2)].dutyType")
                        .value(org.hamcrest.Matchers.contains("RESOURCE_ROOM")))
                .andExpect(jsonPath("$.callOuts[0].reason").value("Synthetic coverage note"))
                .andReturn().getResponse().getContentAsString();
        visits.close(old.id(), old.version());
        coverage.callOut(2, LocalDate.parse("2026-09-14"), "Changed after close");
        walkIn();
        at("2026-09-15T12:30:00Z");
        tickets.resetForOpening();
        occupied();
        mvc.perform(get("/api/snapshot").param("date", "2026-09-14"))
                .andExpect(status().isOk()).andExpect(content().json(snapshot));
        mvc.perform(get("/api/snapshot")).andExpect(content().json(snapshot));
        at("2026-09-15T20:45:00Z");
        mvc.perform(get("/api/snapshot")).andExpect(jsonPath("$.businessDate").value("2026-09-15"))
                .andExpect(jsonPath("$.visits.length()").value(1));
    }

    @Test
    void firstAfterHoursMutationFreezesBeforeChangingVisitEvenWithoutScheduledCapture() {
        Visit old = occupied();
        at("2026-09-14T20:45:00Z");
        visits.left(old.id(), old.version());
        var snapshot = days.snapshot(null);
        assertThat(snapshot.path("visits").get(0).path("status").asText()).isEqualTo("AT_RESOURCE");
        assertThat(visits.detail(old.id()).visit().status()).isEqualTo(Visit.Status.LEFT);
    }

    @Test
    void firstArrivalAfterMissedOpeningCapturesOldOccupancyBeforeReset() {
        Visit old = occupied();
        at("2026-09-15T12:30:00Z");
        Visit current = occupied();
        var snapshot = days.snapshot(LocalDate.parse("2026-09-14"));
        assertThat(snapshot.path("visits").get(0).path("id").asLong()).isEqualTo(old.id());
        assertThat(snapshot.path("resources").findValuesAsText("ticket")).contains(old.ticket());
        assertThat(current.resourceId()).isEqualTo(old.resourceId());
        assertThat(visits.detail(old.id()).visit()).isEqualTo(old);
    }

    @Test
    void snapshotValidatesDatesAndSupportsDaysWithoutVisits() throws Exception {
        mvc.perform(get("/api/snapshot").param("date", "2026-09-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visits.length()").value(0))
                .andExpect(jsonPath("$.resources.length()").value(16));
        for (String date : List.of("bad", "2026-09-14", "2026-09-15", "2026-09-13", "2026-09-07")) {
            mvc.perform(get("/api/snapshot").param("date", date)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void rolloverMigrationBackfillsOwnershipWithoutChangingVisitHistory() {
        String schema = "step9_upgrade";
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("7").load().migrate();
        jdbc.update("""
                INSERT INTO step9_upgrade.visit (ticket, visit_type, reason, status, created_at,
                    customer_id, resource_id, business_date)
                VALUES ('B0001', 'WALK_IN', 'PHONE_ACCESS', 'AT_RESOURCE',
                    TIMESTAMPTZ '2026-09-14 16:00:00+00', 1, 1, DATE '2026-09-14')
                """);
        jdbc.update("UPDATE step9_upgrade.resource SET status = 'OCCUPIED' WHERE id = 1");
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        assertThat(jdbc.queryForObject("SELECT active_visit_id FROM step9_upgrade.resource WHERE id = 1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM step9_upgrade.visit WHERE id = 1", String.class)).isEqualTo("AT_RESOURCE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = 'visit_active_resource_idx'",
                Integer.class, schema)).isZero();
    }

    @Test
    void competingOpeningResetsCannotClearTheFirstNewOccupant() throws Exception {
        Visit old = occupied();
        at("2026-09-15T12:30:00Z");
        try (var executor = Executors.newFixedThreadPool(3)) {
            var results = executor.invokeAll(List.<Callable<Long>>of(
                    () -> { tickets.resetForOpening(); return 0L; },
                    () -> occupied().id(),
                    () -> { tickets.resetForOpening(); return 0L; }));
            for (var result : results) result.get();
            Long currentId = results.get(1).get();
            assertThat(jdbc.queryForObject("SELECT active_visit_id FROM resource WHERE id = ?", Long.class, old.resourceId()))
                    .isEqualTo(currentId);
            assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, old.resourceId()))
                    .isEqualTo("OCCUPIED");
        }
    }

}
