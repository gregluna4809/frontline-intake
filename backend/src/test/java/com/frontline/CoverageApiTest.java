package com.frontline;

import com.frontline.coverage.CoverageService;
import com.frontline.model.StaffDuty;
import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class CoverageApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CoverageService coverage;
    @Autowired VisitService visits;

    @BeforeEach
    void clearCallOutsAndVisits() {
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM visit_event");
        jdbc.execute("DELETE FROM visit");
        jdbc.execute("ALTER TABLE visit ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE visit_event ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("TRUNCATE call_out, business_day_snapshot, business_day_rollover RESTART IDENTITY");
    }

    private LocalDate nextMonday() {
        return LocalDate.now(com.frontline.business.BusinessCalendar.ZONE).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    @Test
    void rotationSeedsEveryDayAndStaggersOneResourceRoomDayPerLsr() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_rotation", Integer.class)).isEqualTo(28);
        for (int day = 0; day < 7; day++) {
            LocalDate date = nextMonday().plusDays(day);
            var rotation = coverage.rotation(date);
            assertThat(rotation).hasSize(4);
            for (int index = 0; index < 4; index++) {
                assertThat(rotation.get(index).dutyType()).isEqualTo(day == index
                        ? StaffDuty.DutyType.RESOURCE_ROOM : StaffDuty.DutyType.APPOINTMENTS);
            }
        }
        mvc.perform(get("/api/rotation").param("date", nextMonday().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].staffId").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Demo Staff 001"))
                .andExpect(jsonPath("$[0].dutyType").value("RESOURCE_ROOM"))
                .andExpect(jsonPath("$[1].dutyType").value("APPOINTMENTS"));
    }

    @Test
    void callOutOverridesOnlyItsDateAndLeavesBaseRotationUntouched() throws Exception {
        LocalDate monday = nextMonday();
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":2,\"date\":\"" + monday + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rotation").param("date", monday.toString()))
                .andExpect(jsonPath("$[1].dutyType").value("RESOURCE_ROOM"));
        mvc.perform(get("/api/rotation").param("date", monday.plusWeeks(1).toString()))
                .andExpect(jsonPath("$[1].dutyType").value("APPOINTMENTS"));
        assertThat(jdbc.queryForObject(
                "SELECT duty_type FROM staff_rotation WHERE staff_id = 2 AND day_of_week = 1", String.class))
                .isEqualTo("APPOINTMENTS");
        mvc.perform(get("/api/call-outs").param("date", monday.plusDays(1).toString()))
                .andExpect(content().json("[]"));
    }

    @Test
    void callOutUpsertsReasonWithoutDuplicatingStaffAndDate() throws Exception {
        LocalDate date = LocalDate.now(com.frontline.business.BusinessCalendar.ZONE);
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":1,\"date\":\"" + date + "\",\"reason\":\"Demo coverage\"}"))
                .andExpect(status().isOk());
        var before = coverage.callOuts(date).getFirst();
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":1,\"date\":\"" + date + "\",\"reason\":\"Updated demo coverage\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(before.id()));
        var updated = coverage.callOuts(date);
        assertThat(updated).hasSize(1);
        assertThat(updated.getFirst().createdAt()).isEqualTo(before.createdAt());
        mvc.perform(get("/api/call-outs").param("date", date.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reason").value("Updated demo coverage"));
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":1,\"date\":\"" + date + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reason").isEmpty());
        assertThat(coverage.callOuts(date)).hasSize(1);
    }

    @Test
    void pastCallOutIsRejectedWithoutWriting() throws Exception {
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":1,\"date\":\"" + LocalDate.now(com.frontline.business.BusinessCalendar.ZONE).minusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM call_out", Integer.class)).isZero();
    }

    @Test
    void invalidStaffAndDatesAreRejectedWithoutWriting() throws Exception {
        for (String body : List.of("{}", "{\"staffId\":1,\"date\":\"invalid\"}",
                "{\"staffId\":0,\"date\":\"" + nextMonday() + "\"}")) {
            mvc.perform(post("/api/call-outs").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":9999,\"date\":\"" + nextMonday() + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":5,\"date\":\"" + nextMonday() + "\"}"))
                .andExpect(status().isConflict());
        for (String path : List.of("/api/rotation", "/api/call-outs")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("date", "invalid")).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM call_out", Integer.class)).isZero();
    }

    @Test
    void concurrentCallOutsProduceOnlyOneRow() throws Exception {
        LocalDate date = nextMonday();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.<Callable<Long>>of(
                    () -> coverage.callOut(1, date, "Demo one").id(),
                    () -> coverage.callOut(1, date, "Demo two").id()));
            assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
        }
        assertThat(coverage.callOuts(date)).hasSize(1);
    }

    @Test
    void callingOutAssignedLsrExposesResourceRoomDutyWithoutChangingAppointments() throws Exception {
        LocalDate date = nextMonday();
        var visit = visits.checkIn(Visit.VisitType.APPOINTMENT, null,
                Visit.AppointmentCategory.JOB_SEARCH_ASSISTANCE);
        visits.identify(visit.id(), 1, 0);
        visits.route(visit.id(), 1);
        visits.assignStaff(visit.id(), 2, 2);
        var before = visits.detail(visit.id());
        mvc.perform(post("/api/call-outs").contentType("application/json")
                        .content("{\"staffId\":2,\"date\":\"" + date + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/rotation").param("date", date.toString()))
                .andExpect(jsonPath("$[1].staffId").value(2))
                .andExpect(jsonPath("$[1].dutyType").value("RESOURCE_ROOM"));
        mvc.perform(get("/api/visits").param("status", "WAITING_STAFF"))
                .andExpect(jsonPath("$[0].assignedStaffId").value(2));
        assertThat(visits.detail(visit.id())).isEqualTo(before);
    }
}
