package com.frontline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frontline.business.BusinessCalendar;
import com.frontline.model.Staff.Role;
import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class AdminApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired BusinessCalendar calendar;
    @Autowired VisitService visits;
    @Autowired PasswordEncoder passwords;
    @MockitoBean(name = "businessClock") Clock clock;
    private final Map<Role, String> tokens = new EnumMap<>(Role.class);

    @BeforeAll
    void loginRoles() throws Exception {
        for (var user : Map.of(Role.LSR, "staff1", Role.SUPERVISING_LSR, "staff5", Role.CLERK, "staff6",
                Role.CENTER_MANAGER, "staff9", Role.ADMINISTRATOR, "staff10").entrySet()) {
            tokens.put(user.getKey(), login(user.getValue(), "demo1234").path("token").asText());
        }
    }

    @BeforeEach
    void reset() {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-14T16:00:00Z"));
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM help_request"); jdbc.execute("DELETE FROM resource_session");
        jdbc.execute("DELETE FROM visit_event"); jdbc.execute("DELETE FROM visit");
        jdbc.execute("TRUNCATE call_out, business_day_snapshot, business_day_rollover RESTART IDENTITY");
        jdbc.execute("DELETE FROM staff_rotation WHERE staff_id > 10");
        jdbc.execute("DELETE FROM user_account WHERE staff_id > 10");
        jdbc.execute("DELETE FROM staff WHERE id > 10");
        jdbc.execute("UPDATE staff SET active = true");
        jdbc.execute("UPDATE staff SET role = 'LSR', display_name = 'Demo Staff 001' WHERE id = 1");
        jdbc.execute("DELETE FROM holiday WHERE date NOT IN (SELECT date FROM holiday WHERE id <= 25)");
        jdbc.execute("UPDATE user_account SET password_hash = '$2a$10$cHjC2Ts/AYJ6lAh3fdFcv.Y6mg5RTx3PQRJ8vHLEHNhb8KLyFPhte'");
    }

    private JsonNode login(String username, String password) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + tokens.get(Role.ADMINISTRATOR));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType("application/json").content(mapper.writeValueAsString(body));
    }

    private long newStaff() {
        return jdbc.queryForObject("INSERT INTO staff (display_name, role) VALUES ('Demo Added Staff', 'CLERK') RETURNING id", Long.class);
    }

    static Stream<Arguments> endpointRoles() {
        return Stream.of("list-staff", "create-staff", "edit-staff", "list-rotation", "edit-rotation", "list-holidays",
                "add-holiday", "remove-holiday", "create-account", "reset-password")
                .flatMap(action -> Stream.of(Role.values()).map(role -> Arguments.of(action, role)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("endpointRoles")
    void onlyAdministratorCanUseEveryAdminEndpoint(String action, Role role) throws Exception {
        long staffId = newStaff();
        long holidayId = jdbc.queryForObject("INSERT INTO holiday (date, label) VALUES ('2028-02-10', 'Demo closure') RETURNING id", Long.class);
        var request = switch (action) {
            case "list-staff" -> get("/api/admin/staff");
            case "create-staff" -> json(post("/api/admin/staff"), Map.of("displayName", "Demo New LSR", "role", "LSR"));
            case "edit-staff" -> json(put("/api/admin/staff/" + staffId), Map.of("displayName", "Demo Edited Staff", "role", "CENTER_MANAGER", "active", false));
            case "list-rotation" -> get("/api/admin/rotation");
            case "edit-rotation" -> json(put("/api/admin/rotation"), Map.of("assignments", List.of(Map.of("staffId", 1, "dayOfWeek", 1, "dutyType", "APPOINTMENTS"))));
            case "list-holidays" -> get("/api/admin/holidays");
            case "add-holiday" -> json(post("/api/admin/holidays"), Map.of("date", "2028-02-11", "label", "Demo holiday"));
            case "remove-holiday" -> delete("/api/admin/holidays/" + holidayId);
            case "create-account" -> json(post("/api/admin/accounts"), Map.of("staffId", staffId, "username", "new.staff", "password", "initial123"));
            default -> json(put("/api/admin/accounts/1/reset-password"), Map.of("password", "changed123"));
        };
        int expected = role != Role.ADMINISTRATOR ? 403 : switch (action) {
            case "create-staff", "add-holiday", "create-account" -> 201;
            case "remove-holiday" -> 204;
            default -> 200;
        };
        var result = mvc.perform(request.header("Authorization", "Bearer " + tokens.get(role))).andExpect(status().is(expected));
        if (expected == 403) result.andExpect(jsonPath("$.detail").value("Your role is not permitted to perform this action."));
    }

    @Test
    void holidaySeedsAndCrudImmediatelyChangeBusinessHours() throws Exception {
        var expected = List.of("2026-01-01", "2026-01-19", "2026-02-16", "2026-05-25", "2026-06-19", "2026-07-04",
                "2026-09-07", "2026-10-12", "2026-11-11", "2026-11-26", "2026-11-27", "2026-12-25", "2027-01-01",
                "2027-01-18", "2027-02-15", "2027-05-31", "2027-06-19", "2027-07-04", "2027-07-05", "2027-09-06",
                "2027-10-11", "2027-11-11", "2027-11-25", "2027-11-26", "2027-12-25");
        assertThat(jdbc.queryForList("SELECT date::text FROM holiday ORDER BY date", String.class)).containsExactlyElementsOf(expected);
        assertThat(calendar.isOpenNow()).isTrue();
        var added = mvc.perform(admin(json(post("/api/admin/holidays"), Map.of("date", "2026-09-14", "label", "Demo special closure"))))
                .andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(added.getResponse().getContentAsString()).path("id").asLong();
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-09-14"))).isFalse();
        mvc.perform(get("/api/business-status")).andExpect(jsonPath("$.isOpen").value(false));
        mvc.perform(admin(delete("/api/admin/holidays/" + id))).andExpect(status().isNoContent());
        mvc.perform(get("/api/business-status")).andExpect(jsonPath("$.isOpen").value(true));
    }

    @Test
    void deactivationRemovesAssignmentTargetsAndRevokesAccessWithoutChangingHistory() throws Exception {
        Visit visit = visits.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        visit = visits.identify(visit.id(), 1, 0).visit();
        visit = visits.route(visit.id(), visit.version()).visit();
        visit = visits.assignStaff(visit.id(), 1, visit.version()).visit();
        var before = visits.detail(visit.id());
        mvc.perform(admin(json(put("/api/admin/staff/1"), Map.of("displayName", "Demo Staff 001", "role", "LSR", "active", false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(visits.detail(visit.id())).isEqualTo(before);
        mvc.perform(admin(get("/api/staff"))).andExpect(jsonPath("$[?(@.id == 1)]").isEmpty());
        mvc.perform(admin(get("/api/rotation?date=2026-09-14"))).andExpect(jsonPath("$[?(@.staffId == 1)]").isEmpty());
        mvc.perform(admin(get("/api/admin/rotation"))).andExpect(jsonPath("$[?(@.staffId == 1)]").isEmpty());
        mvc.perform(admin(get("/api/admin/staff"))).andExpect(jsonPath("$[?(@.id == 1)].active").value(org.hamcrest.Matchers.contains(false)));
        mvc.perform(json(post("/api/visits/" + visit.id() + "/assign-staff"), Map.of("staffId", 1))
                        .header("If-Match", "\"" + visit.version() + "\"").header("Authorization", "Bearer " + tokens.get(Role.CENTER_MANAGER)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/staff").header("Authorization", "Bearer " + tokens.get(Role.LSR))).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/auth/login"), Map.of("username", "staff1", "password", "demo1234"))).andExpect(status().isUnauthorized());
        mvc.perform(admin(json(put("/api/admin/staff/1"), Map.of("displayName", "Demo Staff 001", "role", "LSR", "active", true))))
                .andExpect(status().isOk());
        assertThat(login("staff1", "demo1234").path("staffId").asLong()).isEqualTo(1);
        assertThat(visits.detail(visit.id())).isEqualTo(before);
    }

    @Test
    void accountCreationAndResetChangeLoginImmediatelyAndNeverExposeHashes() throws Exception {
        long staffId = newStaff();
        var result = mvc.perform(admin(json(post("/api/admin/accounts"), Map.of("staffId", staffId, "username", "new.clerk", "password", "initial123"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        assertThat(login("new.clerk", "initial123").path("staffId").asLong()).isEqualTo(staffId);
        mvc.perform(admin(json(put("/api/admin/accounts/" + id + "/reset-password"), Map.of("password", "replacement123")))).andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/login"), Map.of("username", "new.clerk", "password", "initial123"))).andExpect(status().isUnauthorized());
        assertThat(login("new.clerk", "replacement123").path("role").asText()).isEqualTo("CLERK");
        assertThat(passwords.matches("replacement123", jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id = ?", String.class, id))).isTrue();
        mvc.perform(admin(get("/api/admin/staff"))).andExpect(jsonPath("$[?(@.id == " + staffId + ")].username").value(org.hamcrest.Matchers.contains("new.clerk")))
                .andExpect(jsonPath("$[*].passwordHash").isEmpty());
    }

    @Test
    void newLsrsGetSevenDaysAndRotationChangesRemainAtomic() throws Exception {
        var created = mvc.perform(admin(json(post("/api/admin/staff"), Map.of("displayName", "Demo New LSR", "role", "LSR"))))
                .andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_rotation WHERE staff_id = ?", Integer.class, id)).isEqualTo(7);
        var change = Map.of("staffId", id, "dayOfWeek", 1, "dutyType", "RESOURCE_ROOM");
        mvc.perform(admin(json(put("/api/admin/rotation"), Map.of("assignments", List.of(change))))).andExpect(status().isOk());
        mvc.perform(admin(get("/api/rotation?date=2026-09-14")))
                .andExpect(jsonPath("$[?(@.staffId == " + id + ")].dutyType").value(org.hamcrest.Matchers.contains("RESOURCE_ROOM")));
        mvc.perform(admin(json(put("/api/admin/rotation"), Map.of("assignments", List.of(
                Map.of("staffId", id, "dayOfWeek", 1, "dutyType", "APPOINTMENTS"),
                Map.of("staffId", 6, "dayOfWeek", 1, "dutyType", "APPOINTMENTS")))))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT duty_type FROM staff_rotation WHERE staff_id = ? AND day_of_week = 1", String.class, id)).isEqualTo("RESOURCE_ROOM");
    }

    @Test
    void invalidInputsDuplicatesAndMissingRecordsReturnClearClientErrors() throws Exception {
        mvc.perform(admin(json(post("/api/admin/staff"), Map.of("displayName", " ", "role", "LSR")))).andExpect(status().isBadRequest());
        mvc.perform(admin(json(post("/api/admin/staff"), Map.of("displayName", "Demo", "role", "ROOT")))).andExpect(status().isBadRequest());
        mvc.perform(admin(json(put("/api/admin/rotation"), Map.of("assignments", List.of(Map.of("staffId", 1, "dayOfWeek", 8, "dutyType", "APPOINTMENTS")))))).andExpect(status().isBadRequest());
        mvc.perform(admin(json(post("/api/admin/holidays"), Map.of("date", "2026-01-01", "label", "Duplicate")))).andExpect(status().isConflict());
        mvc.perform(admin(json(post("/api/admin/accounts"), Map.of("staffId", 1, "username", "other.username", "password", "initial123")))).andExpect(status().isConflict());
        mvc.perform(admin(json(post("/api/admin/accounts"), Map.of("staffId", newStaff(), "username", "staff1", "password", "initial123")))).andExpect(status().isConflict());
        mvc.perform(admin(json(put("/api/admin/accounts/1/reset-password"), Map.of("password", "short")))).andExpect(status().isBadRequest());
        mvc.perform(admin(delete("/api/admin/holidays/999999"))).andExpect(status().isNotFound());
        mvc.perform(admin(json(put("/api/admin/staff/999999"), Map.of("displayName", "Demo", "role", "LSR")))).andExpect(status().isNotFound());
        mvc.perform(admin(delete("/api/admin/staff/1"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/admin/staff")).andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangesInvalidateOldRoleTokensAndLastAdminCannotBeRemoved() throws Exception {
        mvc.perform(admin(json(put("/api/admin/staff/1"), Map.of("displayName", "Demo Updated", "role", "CLERK", "active", true)))).andExpect(status().isOk());
        mvc.perform(get("/api/staff").header("Authorization", "Bearer " + tokens.get(Role.LSR))).andExpect(status().isUnauthorized());
        assertThat(login("staff1", "demo1234").path("role").asText()).isEqualTo("CLERK");
        mvc.perform(admin(json(put("/api/admin/staff/10"), Map.of("displayName", "Demo Administrator", "role", "ADMINISTRATOR", "active", false)))).andExpect(status().isConflict());
    }

    @Test
    void savedSnapshotsStayReadableAfterRetrospectiveHolidayChanges() throws Exception {
        var before = mvc.perform(get("/api/snapshot?date=2026-09-11")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(admin(json(post("/api/admin/holidays"), Map.of("date", "2026-09-11", "label", "Historical closure"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/snapshot?date=2026-09-11")).andExpect(status().isOk()).andExpect(content().json(before));
    }

    @Test
    void managementRemainsAvailableDuringClosedHours() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T16:00:00Z"));
        assertThat(calendar.isOpenNow()).isFalse();
        mvc.perform(admin(json(post("/api/admin/staff"), Map.of("displayName", "Weekend Demo LSR", "role", "LSR"))))
                .andExpect(status().isCreated());
        mvc.perform(admin(get("/api/admin/rotation"))).andExpect(status().isOk());
        mvc.perform(admin(json(post("/api/admin/holidays"), Map.of("date", "2026-09-22", "label", "Future closure"))))
                .andExpect(status().isCreated());
    }
}
