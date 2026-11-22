package com.frontline;

import com.frontline.business.BusinessCalendar;
import com.frontline.business.BusinessDayService;
import com.frontline.business.BusinessSchedules;
import com.frontline.business.TicketSequenceService;
import com.frontline.coverage.CoverageRepository;
import com.frontline.retention.RetentionJob;
import com.frontline.simulator.SimulatorRepository;
import com.frontline.simulator.TrafficSimulator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"frontline.calendar.always-open=true", "simulator.java-engine.enabled=false",
        "simulator.enabled=true", "business.scheduling.enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class V3PrepTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired BusinessCalendar calendar;
    @Autowired BusinessDayService days;
    @Autowired TicketSequenceService tickets;
    @Autowired TrafficSimulator simulator;
    @Autowired SimulatorRepository engineStaff;
    @Autowired CoverageRepository coverage;
    @Autowired RetentionJob retention;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;

    @Test void alwaysOpenUsesEasternCalendarDateAndDefaultCalendarStillCloses() {
        var standard = TestHolidays.calendar(clock);
        at("2026-11-29T04:00:00Z"); // Saturday at 23:00 Eastern
        assertThat(calendar.status()).isEqualTo(new BusinessCalendar.BusinessStatus(true,
                LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-27")));
        assertThat(standard.isOpenNow()).isFalse();
        at("2026-11-26T17:00:00Z");
        assertThat(calendar.isOpenNow()).isTrue();
        assertThat(calendar.currentBusinessDate()).isEqualTo(LocalDate.parse("2026-11-26"));
        assertThat(standard.isOpenNow()).isFalse();
    }

    @Test void midnightRolloverRunsOnceAndDisabledSimulatorStaysStopped() throws Exception {
        at("2026-11-29T04:59:00Z");
        var schedules = new BusinessSchedules(calendar, simulator, tickets, days);
        schedules.reconcileSimulator();
        long missed = insertAppointment("LATE-APPOINTMENT", "2026-11-28", 1L);
        assertThat(count("SELECT count(*) FROM business_day_rollover WHERE business_date = DATE '2026-11-29'")).isZero();
        assertThat(jdbc.queryForObject("SELECT nextval('visit_ticket_sequence')", Long.class)).isEqualTo(1);
        at("2026-11-29T05:00:00Z");
        schedules.reconcileSimulator();
        assertThat(jdbc.queryForObject("SELECT appointment_status FROM visit WHERE id = ?", String.class, missed))
                .isEqualTo("NOT_ATTENDED_FTR");
        assertThat(count("SELECT count(*) FROM business_day_snapshot WHERE business_date = DATE '2026-11-28'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT nextval('visit_ticket_sequence')", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT nextval('appointment_ticket_sequence')", Long.class)).isEqualTo(1);
        schedules.reconcileSimulator();
        assertThat(jdbc.queryForObject("SELECT nextval('visit_ticket_sequence')", Long.class)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM business_day_rollover WHERE business_date = DATE '2026-11-29'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM visit_event WHERE visit_id = " + missed)).isEqualTo(1);
        simulator.tick();
        assertThat(count("SELECT count(*) FROM sim_day")).isZero();
        mvc.perform(get("/api/simulator/status").header("Authorization", token(9, "CENTER_MANAGER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.running").value(false));
        mvc.perform(post("/api/simulator/start").header("Authorization", token(9, "CENTER_MANAGER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("The Java simulator is disabled."));
    }

    @Test void customerCreationUsesBusinessTimeAndEnforcesValidationAndRoles() throws Exception {
        at("2026-11-29T04:00:00Z");
        String body = """
                {"displayName":"Example Customer","language":"ES","veteran":true}
                """;
        for (var role : List.of("CLERK", "CENTER_MANAGER")) {
            mvc.perform(post("/api/customers").header("Authorization", token(role.equals("CLERK") ? 6 : 9, role))
                        .contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.displayName").value("Example Customer"))
                    .andExpect(jsonPath("$.language").value("ES")).andExpect(jsonPath("$.veteran").value(true))
                    .andExpect(jsonPath("$.createdAt").value("2026-11-29T04:00:00Z"));
        }
        mvc.perform(post("/api/customers").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        for (var roles : List.of("1:LSR", "5:SUPERVISING_LSR", "10:ADMINISTRATOR")) {
            String[] person = roles.split(":");
            mvc.perform(post("/api/customers").header("Authorization", token(Long.parseLong(person[0]), person[1]))
                        .contentType("application/json").content(body))
                    .andExpect(status().isForbidden());
        }
        for (String invalid : List.of("{}", body.replace("Example Customer", " "),
                body.replace("Example Customer", "x".repeat(101)), body.replace("\"ES\"", "\"FR\""),
                body.replace("true", "null"), body.replace("}", ",\"ssn\":\"invalid\"}"))) {
            mvc.perform(post("/api/customers").header("Authorization", token(6, "CLERK"))
                        .contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("SELECT count(*) FROM customer WHERE display_name = 'Example Customer' AND created_at = TIMESTAMPTZ '2026-11-29T04:00:00Z'"))
                .isEqualTo(2);
    }

    @Test void retentionDeletesExpiredRowsAndClearsResourceOwnershipAndRetainedRescheduleLink() {
        at("2026-11-30T06:00:00Z");
        long expiredCustomer = customer("Expired customer", "2026-11-15");
        long retainedCustomer = customer("Retained customer with visit", "2026-11-15");
        long recentCustomer = customer("Recent customer without visits", "2026-11-16");
        long expired = insertAppointment("EXPIRED", "2026-11-15", expiredCustomer);
        long retained = insertAppointment("RETAINED", "2026-11-16", retainedCustomer);
        jdbc.update("UPDATE visit SET previous_visit_id = ? WHERE id = ?", expired, retained);
        for (long id : List.of(expired, retained)) {
            jdbc.update("""
                    INSERT INTO resource_session (visit_id, resource_id, business_date, started_at)
                    SELECT id, ?, business_date, created_at FROM visit WHERE id = ?
                    """, id == expired ? 1 : 2, id);
            jdbc.update("""
                    INSERT INTO help_request (visit_id, session_id, business_date, raised_at, raised_by_staff_id)
                    SELECT visit_id, id, business_date, started_at, 6 FROM resource_session WHERE visit_id = ?
                    """, id);
            jdbc.update("""
                    INSERT INTO sim_task (visit_id, action, due_at, resource_session_id, help_request_id)
                    SELECT visit_id, 'CLAIM_HELP', raised_at, session_id, id FROM help_request WHERE visit_id = ?
                    """, id);
            jdbc.update("""
                    INSERT INTO visit_event (visit_id, to_status, actor, created_at)
                    SELECT id, 'CHECKED_IN', 'KIOSK', created_at FROM visit WHERE id = ?
                    """, id);
            jdbc.update("UPDATE resource SET active_visit_id = ?, status = 'OCCUPIED' WHERE id = ?", id, id == expired ? 1 : 2);
        }
        for (String date : List.of("2026-11-15", "2026-11-16")) {
            var day = LocalDate.parse(date);
            jdbc.update("INSERT INTO call_out (staff_id, date) VALUES (1, ?)", day);
            jdbc.update("INSERT INTO business_day_snapshot (business_date, payload) VALUES (?, '{}')", day);
            jdbc.update("INSERT INTO business_day_rollover (business_date) VALUES (?)", day);
            jdbc.update("INSERT INTO sim_day (business_date, target_arrivals, seed) VALUES (?, 200, 42)", day);
        }
        retention.purge();
        for (String table : List.of("sim_task", "help_request", "resource_session", "visit_event")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE visit_id = " + expired)).isZero();
            assertThat(count("SELECT count(*) FROM " + table + " WHERE visit_id = " + retained)).isEqualTo(1);
        }
        for (String table : List.of("call_out", "business_day_snapshot", "business_day_rollover", "sim_day")) {
            String column = table.equals("call_out") ? "date" : "business_date";
            assertThat(count("SELECT count(*) FROM " + table + " WHERE " + column + " = DATE '2026-11-15'")).isZero();
            assertThat(count("SELECT count(*) FROM " + table + " WHERE " + column + " = DATE '2026-11-16'")).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM visit WHERE id = " + expired)).isZero();
        assertThat(count("SELECT count(*) FROM visit WHERE previous_visit_id IS NULL AND id = " + retained)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resource WHERE id = 1 AND active_visit_id IS NULL AND status = 'FREE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resource WHERE id = 2 AND active_visit_id = " + retained)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM customer WHERE id = " + expiredCustomer)).isZero();
        assertThat(count("SELECT count(*) FROM customer WHERE id IN (" + retainedCustomer + "," + recentCustomer + ")")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM customer WHERE created_at IS NULL")).isEqualTo(50);
    }

    @Test void botStaffHaveSevenDayRotationsNoAccountsAndSortAfterOtherStaff() {
        assertThat(count("SELECT count(*) FROM staff WHERE active AND display_name LIKE 'Bot %'")).isEqualTo(11);
        assertThat(count("SELECT count(*) FROM staff WHERE role = 'CLERK' AND display_name IN ('Bot Clerk 01', 'Bot Clerk 02')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM staff WHERE role = 'SUPERVISING_LSR' AND display_name = 'Bot Supervising LSR 01'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_account u JOIN staff s ON s.id = u.staff_id WHERE s.display_name LIKE 'Bot %'")).isZero();
        var monday = LocalDate.parse("2026-11-30");
        for (int day = 0; day < 7; day++) {
            var rotation = coverage.rotation(monday.plusDays(day));
            assertThat(rotation.subList(0, 4)).allMatch(duty -> !duty.displayName().startsWith("Bot "));
            for (int n = 1; n <= 8; n++) {
                assertThat(rotation.get(n + 3).displayName()).isEqualTo("Bot LSR %02d".formatted(n));
                assertThat(rotation.get(n + 3).dutyType().name()).isEqualTo(n <= 4 ? "RESOURCE_ROOM" : "APPOINTMENTS");
            }
        }
    }

    @Test void javaSimulatorNeverChoosesBotStaffEvenWhenTheyAreTheOnlyAvailableStaff() {
        jdbc.update("UPDATE staff SET active = FALSE WHERE display_name NOT LIKE 'Bot %'");
        var date = LocalDate.parse("2026-11-30");
        assertThat(engineStaff.clerk()).isEmpty();
        assertThat(engineStaff.freeStaff(date, "RESOURCE_ROOM")).isEmpty();
        assertThat(engineStaff.freeStaff(date, "APPOINTMENTS")).isEmpty();
        long bot = jdbc.queryForObject("SELECT id FROM staff WHERE display_name = 'Bot LSR 01'", Long.class);
        assertThat(engineStaff.activeLsr(bot)).isFalse();
    }

    private long insertAppointment(String ticket, String date, long customer) {
        return jdbc.queryForObject("""
                INSERT INTO visit (ticket, visit_type, status, appointment_category, appointment_status,
                    business_date, created_at, customer_id)
                VALUES (?, 'APPOINTMENT', 'CHECKED_IN', 'MANDATORY', 'SCHEDULED', ?, ?::date, ?)
                RETURNING id
                """, Long.class, ticket, LocalDate.parse(date), date, customer);
    }

    private long customer(String name, String date) {
        return jdbc.queryForObject("""
                INSERT INTO customer (display_name, language, veteran, created_at)
                VALUES (?, 'EN', false, ?::date AT TIME ZONE 'America/New_York') RETURNING id
                """, Long.class, name, date);
    }

    private void at(String instant) { when(clock.instant()).thenReturn(Instant.parse(instant)); }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }

    private String token(long id, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("frontline").subject("staff" + id)
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("staffId", id).claim("role", role).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
