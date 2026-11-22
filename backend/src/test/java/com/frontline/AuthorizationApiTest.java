package com.frontline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frontline.model.Staff.Role;
import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired VisitService visits;
    @MockitoBean(name = "businessClock") Clock clock;
    private final Map<Role, String> tokens = new EnumMap<>(Role.class);
    private String otherLsrToken;

    @BeforeAll
    void loginAsEveryRole() throws Exception {
        for (var entry : Map.of(Role.LSR, "staff1", Role.SUPERVISING_LSR, "staff5", Role.CLERK, "staff6",
                Role.CENTER_MANAGER, "staff9", Role.ADMINISTRATOR, "staff10").entrySet()) {
            tokens.put(entry.getKey(), login(entry.getValue()));
        }
        otherLsrToken = login("staff2");
    }

    private String login(String username) throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", "demo1234"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).path("token").asText();
    }

    @BeforeEach
    void reset() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T16:00:00Z"));
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM sim_task");
        jdbc.execute("DELETE FROM help_request");
        jdbc.execute("DELETE FROM resource_session");
        jdbc.execute("DELETE FROM visit_event");
        jdbc.execute("DELETE FROM visit");
        jdbc.execute("TRUNCATE call_out, business_day_snapshot, business_day_rollover RESTART IDENTITY");
    }

    static Stream<Arguments> endpointRoles() {
        return Stream.of("identify", "route", "assign", "close", "left", "assign-staff", "report-ftr", "reschedule", "call-outs")
                .flatMap(action -> Stream.of(Role.values()).map(role -> Arguments.of(action, role)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("endpointRoles")
    void everyEndpointAllowsOnlyItsSpecifiedRoles(String action, Role role) throws Exception {
        Visit visit = prepare(action);
        var before = visits.detail(visit.id());
        var request = mutation(action, visit).header("Authorization", "Bearer " + tokens.get(role));
        boolean allowed = switch (action) {
            case "identify", "route" -> role == Role.CLERK || role == Role.CENTER_MANAGER;
            case "assign", "close", "left" -> role == Role.LSR || role == Role.CENTER_MANAGER;
            default -> role == Role.SUPERVISING_LSR || role == Role.CENTER_MANAGER;
        };
        if (allowed) {
            mvc.perform(request).andExpect(status().is(action.equals("reschedule") ? 201 : 200));
            if (action.equals("call-outs")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM call_out", Integer.class)).isEqualTo(1);
            } else {
                assertThat(visits.detail(visit.id()).visit().version()).isEqualTo(visit.version() + 1);
            }
        } else {
            mvc.perform(request).andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value("Your role is not permitted to perform this action."));
            assertThat(visits.detail(visit.id())).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM call_out", Integer.class)).isZero();
        }
    }

    private Visit prepare(String action) {
        Visit visit = visits.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        if (action.equals("identify") || action.equals("call-outs")) return visit;
        visit = visits.identify(visit.id(), 1, visit.version()).visit();
        if (action.equals("route")) return visit;
        visit = visits.route(visit.id(), visit.version()).visit();
        if (action.equals("assign")) {
            jdbc.update("UPDATE visit SET visit_type = 'WALK_IN', reason = 'PHONE_ACCESS', appointment_type = NULL, appointment_category = NULL, appointment_status = NULL, status = 'WAITING_RESOURCE' WHERE id = ?", visit.id());
            return visits.detail(visit.id()).visit();
        }
        if (action.equals("report-ftr")) {
            jdbc.update("UPDATE visit SET appointment_status = 'NOT_ATTENDED_FTR', status = 'CLOSED', closed_at = now() WHERE id = ?", visit.id());
            return visits.detail(visit.id()).visit();
        }
        if (action.equals("close") || action.equals("left")) {
            return visits.assignStaff(visit.id(), 1, visit.version()).visit();
        }
        return visit;
    }

    private MockHttpServletRequestBuilder mutation(String action, Visit visit) {
        if (action.equals("call-outs")) {
            return post("/api/call-outs").contentType("application/json")
                    .content("{\"staffId\":2,\"date\":\"2026-09-14\",\"reason\":\"Demo coverage\"}");
        }
        var request = post("/api/visits/{id}/" + action, visit.id()).header("If-Match", "\"" + visit.version() + "\"");
        if (action.equals("identify")) request.contentType("application/json").content("{\"customerId\":1}");
        if (action.equals("assign-staff")) request.contentType("application/json").content("{\"staffId\":2}");
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "left"})
    void lsrsCannotFinishOtherOrUnassignedAppointmentsButManagersCan(String action) throws Exception {
        Visit own = prepare(action);
        var before = visits.detail(own.id());
        mvc.perform(mutation(action, own).header("Authorization", "Bearer " + otherLsrToken)
                        .param("staffId", "1").param("role", "CENTER_MANAGER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("LSRs may only close or mark left appointments assigned to themselves."));
        assertThat(visits.detail(own.id())).isEqualTo(before);
        mvc.perform(mutation(action, own).header("Authorization", "Bearer " + tokens.get(Role.LSR)))
                .andExpect(status().isOk());

        Visit unassigned = prepare("assign-staff");
        var unassignedBefore = visits.detail(unassigned.id());
        mvc.perform(mutation(action, unassigned).header("Authorization", "Bearer " + tokens.get(Role.LSR)))
                .andExpect(status().isForbidden());
        assertThat(visits.detail(unassigned.id())).isEqualTo(unassignedBefore);
        mvc.perform(mutation(action, unassigned).header("Authorization", "Bearer " + tokens.get(Role.CENTER_MANAGER)))
                .andExpect(status().isOk());

        Visit other = prepare(action);
        other = visits.assignStaff(other.id(), 2, other.version()).visit();
        mvc.perform(mutation(action, other).header("Authorization", "Bearer " + tokens.get(Role.CENTER_MANAGER)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "left"})
    void lsrCanFinishWalkInsWithoutStaffAssignment(String action) throws Exception {
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        visit = visits.identify(visit.id(), 1, visit.version()).visit();
        visit = visits.route(visit.id(), visit.version()).visit();
        mvc.perform(mutation(action, visit).header("Authorization", "Bearer " + tokens.get(Role.LSR)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"close", "left"})
    void reassignmentRevokesOwnershipEvenWithOldVersion(String action) throws Exception {
        Visit old = prepare(action);
        visits.assignStaff(old.id(), 2, old.version());
        var before = visits.detail(old.id());
        mvc.perform(mutation(action, old).header("Authorization", "Bearer " + tokens.get(Role.LSR)))
                .andExpect(status().isForbidden());
        assertThat(visits.detail(old.id())).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void waitingStaffScopeComesFromJwtAndCannotBeOverridden(Role role) throws Exception {
        Visit own = prepare("close");
        Visit other = prepare("close");
        visits.assignStaff(other.id(), 2, other.version());
        prepare("assign-staff"); // Unassigned appointments are not in an LSR's scoped queue.
        for (boolean explicitDate : List.of(false, true)) {
            var request = get("/api/visits").param("status", "WAITING_STAFF")
                    .param("assignedStaffId", "2").param("staffId", "2").param("role", "CENTER_MANAGER")
                    .header("Authorization", "Bearer " + tokens.get(role));
            if (explicitDate) request.param("businessDate", "2026-09-14");
            var result = mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(role == Role.LSR ? 1 : 3));
            if (role == Role.LSR) result.andExpect(jsonPath("$[0].id").value(own.id()));
        }
        mvc.perform(get("/api/visits").param("status", "WAITING_STAFF")
                        .header("Authorization", "Bearer " + otherLsrToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(other.id()));
        // Only an explicit WAITING_STAFF query is scoped by this step.
        mvc.perform(get("/api/visits").header("Authorization", "Bearer " + tokens.get(role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/visits").param("status", "WAITING_STAFF").param("businessDate", "2026-09-15")
                        .header("Authorization", "Bearer " + tokens.get(role)))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void sharedReadsRemainAvailableToEveryRole(Role role) throws Exception {
        for (String path : List.of("/api/rotation", "/api/call-outs", "/api/staff", "/api/resources")) {
            mvc.perform(get(path).param("date", "2026-09-14").header("Authorization", "Bearer " + tokens.get(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void concurrentReassignmentCannotLetFormerOwnerFinishTheAppointment() throws Exception {
        Visit visit = prepare("close");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> close = () -> mvc.perform(mutation("close", visit)
                    .header("Authorization", "Bearer " + tokens.get(Role.LSR))).andReturn().getResponse().getStatus();
            Callable<Integer> reassign = () -> mvc.perform(mutation("assign-staff", visit)
                    .header("Authorization", "Bearer " + tokens.get(Role.CENTER_MANAGER))).andReturn().getResponse().getStatus();
            var results = executor.invokeAll(List.of(close, reassign));
            var statuses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(statuses).isIn(List.of(200, 412), List.of(403, 200));
            var finalVisit = visits.detail(visit.id()).visit();
            if (finalVisit.assignedStaffId() == 2L) assertThat(finalVisit.status()).isEqualTo(Visit.Status.WAITING_STAFF);
            else assertThat(finalVisit.status()).isEqualTo(Visit.Status.CLOSED);
        }
    }
}
