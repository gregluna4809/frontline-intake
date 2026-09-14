package com.frontline;

import com.frontline.business.BusinessDayService;
import com.frontline.business.TicketSequenceService;
import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@org.springframework.context.annotation.Import(AuthenticatedApiTestConfiguration.class)
@Testcontainers
class FtrApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitService visits;
    @Autowired TicketSequenceService tickets;
    @Autowired BusinessDayService days;
    private int ticket;

    @BeforeEach
    void clearVisits() {
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM visit_event");
        jdbc.execute("DELETE FROM visit");
        jdbc.execute("TRUNCATE business_day_snapshot, business_day_rollover");
        ticket = 0;
        at("2026-09-15T12:30:00Z");
    }

    private void at(String instant) { when(clock.instant()).thenReturn(Instant.parse(instant)); }

    private Visit seed(String date, String type, String appointmentStatus, String status, Long customerId) {
        boolean appointment = type.equals("APPOINTMENT");
        Long id = jdbc.queryForObject("""
                INSERT INTO visit (ticket, visit_type, reason, appointment_category, appointment_type,
                    appointment_status, status, customer_id, business_date, created_at, closed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS date), ?,
                    CASE WHEN ? IN ('CLOSED', 'LEFT') THEN CURRENT_TIMESTAMP ELSE NULL END)
                RETURNING id
                """, Long.class, "TEST" + (++ticket), type, appointment ? null : "PHONE_ACCESS",
                appointment ? "MANDATORY" : null, appointment ? "INITIAL" : null,
                appointmentStatus, status, customerId, date,
                java.sql.Timestamp.from(Instant.now().minusSeconds(86400)), status);
        return visits.detail(id).visit();
    }

    private Visit missed() {
        Visit old = seed("2026-09-14", "APPOINTMENT", "SCHEDULED", "WAITING_STAFF", 1L);
        tickets.resetForOpening();
        return visits.detail(old.id()).visit();
    }

    @Test
    void rolloverClosesOnlyOverdueScheduledAppointmentsAndPreservesSnapshot() {
        var eligible = List.of(
                seed("2026-09-11", "APPOINTMENT", "SCHEDULED", "CHECKED_IN", null),
                seed("2026-09-14", "APPOINTMENT", "SCHEDULED", "IDENTIFIED", 1L),
                seed("2026-09-14", "APPOINTMENT", "SCHEDULED", "WAITING_STAFF", 2L));
        var untouched = List.of(
                seed("2026-09-14", "APPOINTMENT", "SCHEDULED", "CLOSED", 1L),
                seed("2026-09-14", "APPOINTMENT", "SCHEDULED", "LEFT", 1L),
                seed("2026-09-14", "WALK_IN", null, "CHECKED_IN", null),
                seed("2026-09-14", "APPOINTMENT", "RESCHEDULED", "WAITING_STAFF", 1L),
                seed("2026-09-14", "APPOINTMENT", "NOT_ATTENDED_FTR", "CLOSED", 1L),
                seed("2026-09-14", "APPOINTMENT", "FTR_REPORTED", "CLOSED", 1L),
                seed("2026-09-15", "APPOINTMENT", "SCHEDULED", "CHECKED_IN", null),
                seed("2026-09-16", "APPOINTMENT", "SCHEDULED", "CHECKED_IN", null));
        at("2026-09-15T12:29:00Z");
        tickets.resetForOpening();
        eligible.forEach(old -> assertThat(visits.detail(old.id()).visit()).isEqualTo(old));
        at("2026-09-15T12:30:00Z");
        tickets.resetForOpening();
        tickets.resetForOpening();
        for (Visit old : eligible) {
            var detail = visits.detail(old.id());
            assertThat(detail.visit().appointmentStatus()).isEqualTo(Visit.AppointmentStatus.NOT_ATTENDED_FTR);
            assertThat(detail.visit().status()).isEqualTo(Visit.Status.CLOSED);
            assertThat(detail.visit().closedAt()).isNotNull();
            assertThat(detail.visit().version()).isEqualTo(old.version() + 1);
            assertThat(detail.visit().customerId()).isEqualTo(old.customerId());
            assertThat(detail.events()).singleElement().satisfies(event -> {
                assertThat(event.actor()).isEqualTo("SYSTEM");
                assertThat(event.fromStatus()).isEqualTo(old.status());
                assertThat(event.toStatus()).isEqualTo(Visit.Status.CLOSED);
                assertThat(event.note()).contains("Automatic FTR");
            });
        }
        untouched.forEach(old -> assertThat(visits.detail(old.id()).visit()).isEqualTo(old));
        var snapshot = days.snapshot(LocalDate.parse("2026-09-14"));
        var frozen = java.util.stream.StreamSupport.stream(snapshot.path("visits").spliterator(), false)
                .filter(visit -> visit.path("id").asLong() == eligible.get(1).id()).findFirst().orElseThrow();
        assertThat(frozen.path("appointmentStatus").asText()).isEqualTo("SCHEDULED");
        assertThat(frozen.path("status").asText()).isEqualTo("IDENTIFIED");
    }

    @Test
    void failedSystemEventRollsBackFtrAndRollover() {
        Visit old = seed("2026-09-14", "APPOINTMENT", "SCHEDULED", "WAITING_STAFF", 1L);
        jdbc.execute("UPDATE resource SET status = 'OCCUPIED' WHERE id = 1");
        jdbc.execute("ALTER TABLE visit_event ADD CONSTRAINT test_reject_system CHECK (actor <> 'SYSTEM')");
        try {
            assertThatThrownBy(() -> tickets.resetForOpening()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(visits.detail(old.id()).visit()).isEqualTo(old);
            assertThat(visits.detail(old.id()).events()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM business_day_rollover", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = 1", String.class)).isEqualTo("OCCUPIED");
        } finally { jdbc.execute("ALTER TABLE visit_event DROP CONSTRAINT test_reject_system"); }
        tickets.resetForOpening();
        assertThat(visits.detail(old.id()).visit().appointmentStatus()).isEqualTo(Visit.AppointmentStatus.NOT_ATTENDED_FTR);
    }

    @Test
    void reportFtrChangesOnlyAppointmentStatusAndVersionAndRecordsContact() throws Exception {
        Visit old = missed();
        mvc.perform(post("/api/visits/{id}/report-ftr", old.id()).header("If-Match", "\"" + old.version() + "\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.visit.appointmentStatus").value("FTR_REPORTED"))
                .andExpect(jsonPath("$.visit.status").value("CLOSED"));
        var detail = visits.detail(old.id());
        assertThat(detail.visit().closedAt()).isEqualTo(old.closedAt());
        assertThat(detail.visit().assignedStaffId()).isEqualTo(old.assignedStaffId());
        assertThat(detail.visit().customerId()).isEqualTo(old.customerId());
        assertThat(detail.events()).hasSize(2);
        assertThat(detail.events().getLast().actor()).isEqualTo("APPOINTMENTS");
        assertThat(detail.events().getLast().note()).contains("FTR_REPORTED");
        assertThat(detail.events().getLast().fromStatus()).isEqualTo(Visit.Status.CLOSED);
        assertThat(detail.events().getLast().toStatus()).isEqualTo(Visit.Status.CLOSED);
    }

    @Test
    void reportFtrEnforcesIfMatchAndDoesNotChangeStateOnRejection() throws Exception {
        Visit old = missed();
        var before = visits.detail(old.id());
        mvc.perform(post("/api/visits/{id}/report-ftr", old.id())).andExpect(status().is(428));
        for (String value : List.of("1", "*", "W/\"1\"", "\"-1\"", "\"99999999999999999999999\"")) {
            mvc.perform(post("/api/visits/{id}/report-ftr", old.id()).header("If-Match", value)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/visits/{id}/report-ftr", old.id()).header("If-Match", "\"0\""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post("/api/visits/999999/report-ftr").header("If-Match", "\"0\""))
                .andExpect(status().isNotFound());
        assertThat(visits.detail(old.id())).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULED", "RESCHEDULED", "FTR_REPORTED"})
    void reportFtrRejectsWrongStartingAppointmentStatus(String appointmentStatus) throws Exception {
        Visit visit = seed("2026-09-15", "APPOINTMENT", appointmentStatus, "CLOSED", 1L);
        var before = visits.detail(visit.id());
        mvc.perform(post("/api/visits/{id}/report-ftr", visit.id()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict());
        assertThat(visits.detail(visit.id())).isEqualTo(before);
    }

    @Test
    void concurrentReportsProduceOneContactEvent() throws Exception {
        Visit old = missed();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> report = () -> mvc.perform(post("/api/visits/{id}/report-ftr", old.id())
                    .header("If-Match", "\"" + old.version() + "\"" )).andReturn().getResponse().getStatus();
            var results = executor.invokeAll(List.of(report, report));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 412);
        }
        assertThat(visits.detail(old.id()).events()).hasSize(2);
    }

    @Test
    void identifyReturnsMostRecentOtherAppointmentFtrAndReloadClearsItAfterContact() throws Exception {
        Visit old = missed();
        Visit current = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        mvc.perform(post("/api/visits/{id}/identify", current.id()).header("If-Match", "\"0\"")
                        .contentType("application/json").content("{\"customerId\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.missedAppointment.id").value(old.id()));
        mvc.perform(get("/api/visits/{id}", current.id())).andExpect(jsonPath("$.missedAppointment.id").value(old.id()));
        visits.reportFtr(old.id(), old.version());
        mvc.perform(get("/api/visits/{id}", current.id())).andExpect(jsonPath("$.missedAppointment").isEmpty());
    }

    @Test
    void newerAppointmentSupersedesOlderFtrButWalkInsDoNotAndOtherCustomersAreIgnored() {
        Visit old = missed();
        Visit walkIn = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
        assertThat(visits.identify(walkIn.id(), 1, 0).missedAppointment().id()).isEqualTo(old.id());
        Visit other = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
        assertThat(visits.identify(other.id(), 2, 0).missedAppointment()).isNull();
        Visit currentAppointment = visits.checkIn(Visit.VisitType.APPOINTMENT, null,
                Visit.AppointmentCategory.MANDATORY);
        // The appointment being identified must not hide its own customer's history.
        assertThat(visits.identify(currentAppointment.id(), 1, 0).missedAppointment().id()).isEqualTo(old.id());
        assertThat(visits.detail(walkIn.id()).missedAppointment()).isNull();
    }
    @Test
    void failedContactEventRollsBackAppointmentStatusAndVersion() {
        Visit old = missed();
        var before = visits.detail(old.id());
        jdbc.execute("ALTER TABLE visit_event ADD CONSTRAINT test_reject_contact CHECK (actor <> 'APPOINTMENTS')");
        try {
            assertThatThrownBy(() -> visits.reportFtr(old.id(), old.version()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(visits.detail(old.id())).isEqualTo(before);
        } finally { jdbc.execute("ALTER TABLE visit_event DROP CONSTRAINT test_reject_contact"); }
    }

    @Test
    void walkInWithoutAppointmentStatusCannotReportFtr() throws Exception {
        Visit visit = seed("2026-09-15", "WALK_IN", null, "CHECKED_IN", null);
        mvc.perform(post("/api/visits/{id}/report-ftr", visit.id()).header("If-Match", "\"0\""))
                .andExpect(status().isConflict());
        assertThat(visits.detail(visit.id()).visit()).isEqualTo(visit);
        assertThat(visits.detail(visit.id()).events()).isEmpty();
    }

}
