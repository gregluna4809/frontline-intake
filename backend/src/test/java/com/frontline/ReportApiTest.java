package com.frontline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frontline.model.Staff.Role;
import com.frontline.model.Visit;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class ReportApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    private static final LocalDate DAY = LocalDate.of(2028, 3, 6);
    private static final Instant CREATED = Instant.parse("2028-03-06T14:00:00Z");
    private int ticket;

    private MockHttpServletRequestBuilder authorized(String query, Role role) {
        long staffId = Map.of(Role.LSR, 1L, Role.SUPERVISING_LSR, 5L, Role.CLERK, 6L,
                Role.CENTER_MANAGER, 9L, Role.ADMINISTRATOR, 10L).get(role);
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("frontline").subject("staff" + staffId)
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("staffId", staffId)
                .claim("role", role.name()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return get("/api/reports/summary" + query).header("Authorization", "Bearer " + token);
    }

    private JsonNode summary(LocalDate from, LocalDate to) throws Exception {
        return mapper.readTree(mvc.perform(authorized("?from=" + from + "&to=" + to, Role.CENTER_MANAGER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyManagersAndAdministratorsCanReadReports(Role role) throws Exception {
        boolean allowed = role == Role.CENTER_MANAGER || role == Role.ADMINISTRATOR;
        var result = mvc.perform(authorized("?from=" + DAY + "&to=" + DAY, role))
                .andExpect(status().is(allowed ? 200 : 403));
        if (!allowed) result.andExpect(jsonPath("$.detail").value("Your role is not permitted to perform this action."));
    }

    @Test
    void unauthenticatedRequestsRequireLogin() throws Exception {
        mvc.perform(get("/api/reports/summary?from=" + DAY + "&to=" + DAY)).andExpect(status().isUnauthorized());
    }

    @Test
    void validatesOrderAndInclusiveNinetyDayLimit() throws Exception {
        mvc.perform(authorized("?from=" + DAY.plusDays(1) + "&to=" + DAY, Role.CENTER_MANAGER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("from must be on or before to."));
        mvc.perform(authorized("?from=" + DAY + "&to=" + DAY.plusDays(90), Role.CENTER_MANAGER))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(
                        "Report range must not exceed 90 calendar days, including both endpoints."));
        assertThat(summary(DAY, DAY.plusDays(89)).isArray()).isTrue();
        assertThat(summary(DAY, DAY).size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?from=2028-03-06", "?to=2028-03-06", "?from=invalid&to=2028-03-06",
            "?from=2028-02-30&to=2028-03-06"})
    void missingOrMalformedDatesAreClientErrors(String query) throws Exception {
        mvc.perform(authorized(query, Role.CENTER_MANAGER)).andExpect(status().isBadRequest());
    }

    @Test
    void computesEveryCategoryFtrFractionWaitDurationAndWorkload() throws Exception {
        long phone = walkIn("PHONE_ACCESS");
        event(phone, "IDENTIFIED", "WAITING_RESOURCE", CREATED.plusSeconds(120));
        event(phone, "WAITING_RESOURCE", "AT_RESOURCE", CREATED.plusSeconds(630));
        // Same-status notes and later arrivals must not weight a visit more than once.
        event(phone, "AT_RESOURCE", "AT_RESOURCE", CREATED.plusSeconds(900));
        event(phone, "WAITING_RESOURCE", "AT_RESOURCE", CREATED.plusSeconds(3600));
        long anotherPhone = walkIn("PHONE_ACCESS");
        event(anotherPhone, "WAITING_RESOURCE", "AT_RESOURCE", CREATED.plusSeconds(1770));
        for (Visit.Reason reason : Visit.Reason.values()) {
            if (reason != Visit.Reason.PHONE_ACCESS) walkIn(reason.name());
        }
        // A closed walk-in contributes to volume, but never appointment duration/workload.
        jdbc.update("UPDATE visit SET status = 'CLOSED', closed_at = ? WHERE id = ?",
                Timestamp.from(CREATED.plusSeconds(7200)), phone);
        appointment(DAY, "MANDATORY", "SCHEDULED", "CLOSED", 30, 1L, null);
        long missed = appointment(DAY, "MANDATORY", "NOT_ATTENDED_FTR", "CLOSED", 90, 1L, null);
        appointment(DAY, "JOB_SEARCH_ASSISTANCE", "FTR_REPORTED", "CLOSED", 1500, 2L, null);
        appointment(DAY, "MANDATORY", "RESCHEDULED", "CLOSED", 60, null, missed);
        appointment(DAY, "JOB_SEARCH_ASSISTANCE", "SCHEDULED", "WAITING_STAFF", null, 1L, null);
        appointment(DAY, "MANDATORY", "SCHEDULED", "LEFT", 300, 1L, null);
        jdbc.update("UPDATE staff SET active = false WHERE id = 2");
        callOut(DAY, 1);
        callOut(DAY, 2);

        JsonNode report = summary(DAY, DAY).get(0);
        assertThat(report.path("businessDate").asText()).isEqualTo(DAY.toString());
        JsonNode volume = report.path("volumeByCategory");
        assertThat(volume.size()).isEqualTo(8);
        for (Visit.Reason reason : Visit.Reason.values()) {
            assertThat(volume.path(reason.name()).asLong()).isEqualTo(reason == Visit.Reason.PHONE_ACCESS ? 2 : 1);
        }
        assertThat(volume.path("MANDATORY").asLong()).isEqualTo(4);
        assertThat(volume.path("JOB_SEARCH_ASSISTANCE").asLong()).isEqualTo(2);
        assertThat(report.path("ftr").path("scheduledAppointments").asLong()).isEqualTo(6);
        assertThat(report.path("ftr").path("ftrCount").asLong()).isEqualTo(2);
        assertThat(report.path("ftr").path("ftrRate").asDouble()).isCloseTo(1.0 / 3, within(0.0000001));
        assertThat(report.path("avgResourceWaitMinutes").asDouble()).isEqualTo(20.0);
        // Includes all four CLOSED appointments, including FTR/rescheduled/unassigned; excludes LEFT.
        assertThat(report.path("avgAppointmentDurationMinutes").asDouble()).isEqualTo(420.0);
        JsonNode workload = report.path("staffWorkload");
        assertThat(workload.size()).isEqualTo(2);
        assertThat(workload.get(0).path("staffId").asLong()).isEqualTo(1);
        assertThat(workload.get(0).path("closedCount").asLong()).isEqualTo(2);
        assertThat(workload.get(1).path("staffId").asLong()).isEqualTo(2);
        assertThat(workload.get(1).path("closedCount").asLong()).isEqualTo(1);
        assertThat(workload.get(1).path("displayName").asText()).isEqualTo(
                jdbc.queryForObject("SELECT display_name FROM staff WHERE id = 2", String.class));
        assertThat(report.path("callOutCount").asLong()).isEqualTo(2);
        assertThat(report.path("rescheduleCount").asLong()).isEqualTo(1);
    }

    @Test
    void usesBusinessDateEvenWhenTimestampsDifferAndCountsReschedulesIndependently() throws Exception {
        long original = appointment(DAY, "MANDATORY", "SCHEDULED", "CLOSED", 1500, 1L, null);
        appointment(DAY.plusDays(1), "MANDATORY", "RESCHEDULED", "CLOSED", 15, 2L, original);
        // This visit closes inside the requested dates but belongs to an earlier business date.
        appointment(DAY.minusDays(3), "MANDATORY", "FTR_REPORTED", "CLOSED", 3000, 1L, null);
        callOut(DAY, 1);
        JsonNode reports = summary(DAY, DAY.plusDays(1));
        assertThat(reports.size()).isEqualTo(2);
        JsonNode first = reports.get(0), second = reports.get(1);
        assertThat(first.path("ftr").path("scheduledAppointments").asLong()).isEqualTo(1);
        assertThat(first.path("ftr").path("ftrCount").asLong()).isZero();
        assertThat(first.path("avgAppointmentDurationMinutes").asDouble()).isEqualTo(1500);
        assertThat(first.path("staffWorkload").get(0).path("closedCount").asLong()).isEqualTo(1);
        assertThat(first.path("callOutCount").asLong()).isEqualTo(1);
        assertThat(first.path("rescheduleCount").asLong()).isZero();
        assertThat(second.path("businessDate").asText()).isEqualTo(DAY.plusDays(1).toString());
        assertThat(second.path("avgAppointmentDurationMinutes").asDouble()).isEqualTo(15);
        assertThat(second.path("volumeByCategory").path("MANDATORY").asLong()).isEqualTo(1);
        assertThat(second.path("ftr").path("scheduledAppointments").asLong()).isEqualTo(1);
        assertThat(second.path("ftr").path("ftrRate").asDouble()).isZero();
        assertThat(second.path("callOutCount").asLong()).isZero();
        assertThat(second.path("rescheduleCount").asLong()).isEqualTo(1);
    }

    @Test
    void emptyBusinessDaysHaveZerosAndNullsAndCalendarExcludesWeekendsAndHolidays() throws Exception {
        jdbc.update("INSERT INTO holiday (date, label) VALUES (?, 'Report test holiday')", DAY.plusDays(2));
        JsonNode reports = summary(DAY, DAY.plusDays(6));
        assertThat(reports.size()).isEqualTo(4);
        assertThat(reports.get(0).path("businessDate").asText()).isEqualTo(DAY.toString());
        assertThat(reports.get(3).path("businessDate").asText()).isEqualTo(DAY.plusDays(4).toString());
        for (JsonNode report : reports) {
            assertThat(report.path("volumeByCategory").size()).isEqualTo(8);
            report.path("volumeByCategory").forEach(count -> assertThat(count.asLong()).isZero());
            assertThat(report.path("ftr").path("scheduledAppointments").asLong()).isZero();
            assertThat(report.path("ftr").path("ftrCount").asLong()).isZero();
            assertThat(report.path("ftr").path("ftrRate").asDouble()).isZero();
            assertThat(report.get("avgResourceWaitMinutes").isNull()).isTrue();
            assertThat(report.get("avgAppointmentDurationMinutes").isNull()).isTrue();
            assertThat(report.path("staffWorkload").isEmpty()).isTrue();
            assertThat(report.path("callOutCount").asLong()).isZero();
            assertThat(report.path("rescheduleCount").asLong()).isZero();
        }
        assertThat(summary(DAY.plusDays(5), DAY.plusDays(6)).isEmpty()).isTrue();
        assertThat(summary(DAY.plusDays(2), DAY.plusDays(2)).isEmpty()).isTrue();
    }

    @Test
    void activityWithoutQualifyingEventsOrClosuresStillHasNullAverages() throws Exception {
        walkIn("OTHER");
        appointment(DAY, "MANDATORY", "SCHEDULED", "WAITING_STAFF", null, 1L, null);
        JsonNode report = summary(DAY, DAY).get(0);
        assertThat(report.path("volumeByCategory").path("OTHER").asLong()).isEqualTo(1);
        assertThat(report.get("avgResourceWaitMinutes").isNull()).isTrue();
        assertThat(report.get("avgAppointmentDurationMinutes").isNull()).isTrue();
        assertThat(report.path("staffWorkload").isEmpty()).isTrue();
    }

    private long walkIn(String reason) {
        return jdbc.queryForObject("""
                INSERT INTO visit (ticket, visit_type, reason, status, created_at, business_date)
                VALUES (?, 'WALK_IN', ?, 'WAITING_RESOURCE', ?, ?) RETURNING id
                """, Long.class, "REPORT-" + ++ticket, reason, Timestamp.from(CREATED), DAY);
    }

    private long appointment(LocalDate date, String category, String appointmentStatus, String status,
                             Integer durationMinutes, Long staffId, Long previousId) {
        return jdbc.queryForObject("""
                INSERT INTO visit (ticket, visit_type, appointment_category, appointment_status, status,
                    created_at, closed_at, business_date, assigned_staff_id, previous_visit_id)
                VALUES (?, 'APPOINTMENT', ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "REPORT-" + ++ticket, category, appointmentStatus, status, Timestamp.from(CREATED),
                durationMinutes == null ? null : Timestamp.from(CREATED.plusSeconds(durationMinutes * 60L)),
                date, staffId, previousId);
    }

    private void event(long visitId, String from, String to, Instant time) {
        jdbc.update("INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at) VALUES (?, ?, ?, 'TEST', ?)",
                visitId, from, to, Timestamp.from(time));
    }

    private void callOut(LocalDate date, long staffId) {
        jdbc.update("INSERT INTO call_out (staff_id, date) VALUES (?, ?)", staffId, date);
    }
}
