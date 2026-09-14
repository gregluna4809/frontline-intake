package com.frontline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frontline.business.BusinessCalendar;
import com.frontline.visit.VisitService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class SeedDayMigrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired VisitService visits;
    @Autowired ObjectMapper mapper;
    @Autowired Flyway flyway;
    private static final LocalDate DATE = LocalDate.parse("2026-09-11");

    @BeforeEach
    void setClock() { when(clock.instant()).thenReturn(Instant.parse("2026-09-13T16:00:00Z")); }

    @Test
    void seededDayHasTerminalVisitsMatchingSnapshotAndRealisticMix() throws Exception {
        assertThat(new BusinessCalendar(clock).isBusinessDay(DATE)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE business_date = ?", Integer.class, DATE)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE business_date = ? AND (status NOT IN ('CLOSED', 'LEFT') OR closed_at IS NULL)", Integer.class, DATE)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE reason = 'PHONE_ACCESS'", Integer.class)).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE visit_type = 'APPOINTMENT'", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForList("SELECT DISTINCT reason FROM visit WHERE reason IS NOT NULL", String.class))
                .containsExactlyInAnyOrder("PHONE_ACCESS", "COMPUTER_ACCESS", "JOB_SEARCH", "DOCUMENT_SERVICES", "BENEFITS_ASSISTANCE", "OTHER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE appointment_category = 'MANDATORY'", Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE appointment_category = 'JOB_SEARCH_ASSISTANCE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE appointment_type = 'INITIAL'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE appointment_type = 'FOLLOWUP'", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE appointment_status = 'NOT_ATTENDED_FTR'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit WHERE status = 'LEFT'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE status <> 'FREE' OR active_visit_id IS NOT NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit_event", Integer.class)).isEqualTo(236);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit v WHERE (SELECT count(*) FROM visit_event e WHERE e.visit_id = v.id) <> v.version + 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM visit v WHERE v.appointment_type = 'FOLLOWUP' AND NOT EXISTS (
                    SELECT 1 FROM visit prior WHERE prior.customer_id = v.customer_id
                    AND prior.visit_type = 'APPOINTMENT' AND prior.created_at < v.created_at)
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM visit a JOIN visit b ON a.resource_id = b.resource_id AND a.id < b.id
                WHERE a.created_at + INTERVAL '5 minutes' < b.closed_at
                    AND b.created_at + INTERVAL '5 minutes' < a.closed_at
                """, Integer.class)).isZero();
        var payload = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM business_day_snapshot WHERE business_date = ?", String.class, DATE));
        var live = mapper.readTree(mapper.writeValueAsString(visits.list(null, DATE)));
        assertThat(payload.path("visits").size()).isEqualTo(live.size());
        for (int i = 0; i < live.size(); i++) {
            var historicalVisit = payload.path("visits").get(i).deepCopy();
            // PostgreSQL formats equivalent UTC instants with six fractional digits.
            assertThat(Instant.parse(historicalVisit.path("createdAt").asText())).isEqualTo(Instant.parse(live.get(i).path("createdAt").asText()));
            assertThat(Instant.parse(historicalVisit.path("closedAt").asText())).isEqualTo(Instant.parse(live.get(i).path("closedAt").asText()));
            ((com.fasterxml.jackson.databind.node.ObjectNode) historicalVisit).set("createdAt", live.get(i).path("createdAt"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) historicalVisit).set("closedAt", live.get(i).path("closedAt"));
            assertThat(historicalVisit).isEqualTo(live.get(i));
        }
        mvc.perform(get("/api/snapshot").param("date", DATE.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.visits.length()").value(60)).andExpect(jsonPath("$.resources.length()").value(16))
                .andExpect(jsonPath("$.staff.length()").value(5)).andExpect(jsonPath("$.coverage.length()").value(4))
                .andExpect(jsonPath("$.callOuts.length()").value(0));
        for (var resource : payload.path("resources")) {
            assertThat(resource.path("status").asText()).isEqualTo("FREE");
            assertThat(resource.path("visitId").isNull()).isTrue();
        }
        for (var duty : payload.path("coverage")) assertThat(duty.path("dutyType").asText()).isEqualTo("APPOINTMENTS");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM visit WHERE business_date = DATE '2026-09-11'
                AND (created_at < TIMESTAMPTZ '2026-09-11 08:30:00 America/New_York'
                    OR closed_at > TIMESTAMPTZ '2026-09-11 16:45:00 America/New_York' OR closed_at < created_at)
                """, Integer.class)).isZero();
        for (String sequence : List.of("visit_ticket_sequence", "appointment_ticket_sequence")) {
            assertThat(jdbc.queryForObject("SELECT is_called FROM " + sequence, Boolean.class)).isFalse();
        }
        flyway.migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM visit", Integer.class)).isEqualTo(60);
    }

    @Test
    void upgradePreservesExistingFrozenDataLiveOccupancyAndTicketCounters() throws Exception {
        String schema = "step12_upgrade";
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("9").load().migrate();
        jdbc.execute("""
                INSERT INTO step12_upgrade.visit (ticket, visit_type, reason, status, created_at, business_date)
                VALUES ('B0001', 'WALK_IN', 'OTHER', 'CLOSED', TIMESTAMPTZ '2026-09-11 09:00:00-04', DATE '2026-09-11'),
                    ('B0002', 'WALK_IN', 'OTHER', 'CHECKED_IN', TIMESTAMPTZ '2026-09-11 18:00:00-04', DATE '2026-09-11')
                """);
        jdbc.execute("UPDATE step12_upgrade.resource SET status = 'OCCUPIED' WHERE id = 1");
        jdbc.execute("ALTER SEQUENCE step12_upgrade.visit_ticket_sequence RESTART WITH 77");
        jdbc.execute("""
                INSERT INTO step12_upgrade.business_day_snapshot (business_date, payload)
                VALUES (DATE '2026-09-11', '{"businessDate":"2026-09-11","visits":[{"id":1,"ticket":"B0001","status":"LEFT","createdAt":"2026-09-11T13:00:00Z"}],"resources":[{"id":1,"status":"OCCUPIED"}],"staff":[],"coverage":[],"callOuts":[]}'::jsonb)
                """);
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        var payload = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM step12_upgrade.business_day_snapshot", String.class));
        assertThat(payload.path("visits").size()).isEqualTo(61);
        assertThat(java.util.stream.StreamSupport.stream(payload.path("visits").spliterator(), false)
                .filter(visit -> visit.path("id").asLong() == 1).findFirst().orElseThrow().path("status").asText()).isEqualTo("LEFT");
        assertThat(payload.path("resources").get(0).path("status").asText()).isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT status FROM step12_upgrade.resource WHERE id = 1", String.class)).isEqualTo("OCCUPIED");
        assertThat(jdbc.queryForObject("SELECT last_value FROM step12_upgrade.visit_ticket_sequence", Long.class)).isEqualTo(77);
        assertThat(jdbc.queryForObject("SELECT status FROM step12_upgrade.visit WHERE id = 1", String.class)).isEqualTo("CLOSED");
    }
}
