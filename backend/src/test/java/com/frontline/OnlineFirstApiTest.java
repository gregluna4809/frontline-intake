package com.frontline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frontline.business.BusinessDayService;
import com.frontline.model.Staff.Role;
import com.frontline.model.Visit;
import com.frontline.model.HelpRequest;
import com.frontline.simulator.SimulatorProperties;
import com.frontline.simulator.TrafficSimulator;
import com.frontline.visit.VisitReasonRepository;
import com.frontline.visit.VisitService;
import com.frontline.visit.VisitRepository;
import com.frontline.help.HelpRequestService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "business.scheduling.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class OnlineFirstApiTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisitService visits;
    @Autowired BusinessDayService days;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder encoder;
    @Autowired VisitReasonRepository reasons;
    @Autowired HelpRequestService helpRequests;
    @MockitoSpyBean VisitRepository visitRepository;
    @Autowired TrafficSimulator simulator;
    @Autowired com.frontline.simulator.SimulatorRepository simulatorRepository;

    @BeforeEach
    void reset() {
        at("2026-11-30");
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM sim_task");
        jdbc.execute("DELETE FROM help_request");
        jdbc.execute("DELETE FROM resource_session");
        jdbc.execute("DELETE FROM visit_event");
        jdbc.execute("DELETE FROM visit");
        jdbc.execute("TRUNCATE business_day_snapshot, business_day_rollover");
    }
    private void at(String date) { when(clock.instant()).thenReturn(Instant.parse(date + "T17:00:00Z")); }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Role role) {
        long staff = Map.of(Role.LSR, 1L, Role.SUPERVISING_LSR, 5L, Role.CLERK, 6L,
                Role.CENTER_MANAGER, 9L, Role.ADMINISTRATOR, 10L).get(role);
        return asStaff(request, role, staff);
    }
    private MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request, Role role, long staff) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("frontline").subject("staff" + staff).issuedAt(now)
                .expiresAt(now.plusSeconds(300)).claim("staffId", staff).claim("role", role.name()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return request.header("Authorization", "Bearer " + token);
    }
    private MockHttpServletRequestBuilder mutation(String path, long version) {
        return post(path).header("If-Match", "\"" + version + "\"").contentType(MediaType.APPLICATION_JSON);
    }
    private Visit computer() {
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT, null);
        visits.identify(visit.id(), 1, 0);
        return visits.route(visit.id(), 1).visit();
    }
    private JsonNode raise(Visit visit) throws Exception {
        return mapper.readTree(mvc.perform(as(mutation("/api/visits/" + visit.id() + "/help-requests", visit.version()), Role.CLERK))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andReturn().getResponse().getContentAsString());
    }
    private JsonNode claim(JsonNode help) throws Exception {
        return mapper.readTree(mvc.perform(as(mutation("/api/help-requests/" + help.path("id").asLong() + "/claim", help.path("version").asLong()), Role.LSR))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andReturn().getResponse().getContentAsString());
    }
    private void closeHelp(JsonNode help, String outcome) throws Exception {
        mvc.perform(as(mutation("/api/help-requests/" + help.path("id").asLong() + "/close", help.path("version").asLong())
                .content(mapper.writeValueAsString(Map.of("outcome", outcome))), Role.LSR))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    @Test
    void anonymousReasonsSwitchAtCutover() throws Exception {
        JsonNode before = mapper.readTree(mvc.perform(get("/api/visit-reasons?date=2026-11-25"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode after = mapper.readTree(mvc.perform(get("/api/visit-reasons?date=2026-11-30"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(before.findValuesAsText("code")).contains("PHONE_ACCESS").doesNotContain("UI_ACCOUNT");
        assertThat(after.findValuesAsText("code")).contains("UI_ACCOUNT").doesNotContain("PHONE_ACCESS");
        assertThat(after.get(0).get("label").asText()).isEqualTo("UI account help");
    }

    @Test
    void inactiveReasonsAreRejectedOnCreateAndIdentify() throws Exception {
        mvc.perform(post("/api/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitType\":\"WALK_IN\",\"reason\":\"PHONE_ACCESS\"}"))
                .andExpect(status().isBadRequest());
        at("2026-11-25");
        mvc.perform(post("/api/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitType\":\"WALK_IN\",\"reason\":\"UI_ACCOUNT\"}"))
                .andExpect(status().isBadRequest());
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        jdbc.update("UPDATE visit SET reason = 'UI_ACCOUNT' WHERE id = ?", visit.id());
        mvc.perform(as(mutation("/api/visits/" + visit.id() + "/identify", 0)
                .content("{\"customerId\":1}"), Role.CLERK)).andExpect(status().isBadRequest());
    }

    @Test
    void uiAccountRoutesToComputerWithExactlyOneSession() {
        Visit visit = computer();
        assertThat(visits.detail(visit.id()).resource().type().name()).isEqualTo("COMPUTER");
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ? AND ended_at IS NULL", visit.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT business_date FROM resource_session WHERE visit_id = ?", LocalDate.class, visit.id()))
                .isEqualTo(LocalDate.parse("2026-11-30"));
    }

    @Test
    void routeAndAssignNeverUsePhonesWithoutEffectivePhoneType() throws Exception {
        for (var reason : reasons.active(LocalDate.parse("2026-11-30"))) {
            Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.valueOf(reason.code()), null);
            visits.identify(visit.id(), 1, 0);
            var routed = visits.route(visit.id(), 1);
            assertThat(routed.requiredResourceType().name()).isNotEqualTo("PHONE");
            if (routed.resource() != null) assertThat(routed.resource().type().name()).isNotEqualTo("PHONE");
        }
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'COMPUTER'");
        Visit waiting = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT, null);
        visits.identify(waiting.id(), 1, 0);
        assertThat(visits.route(waiting.id(), 1).visit().status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
        mvc.perform(as(mutation("/api/visits/" + waiting.id() + "/assign", 2), Role.LSR)).andExpect(status().isConflict());
        jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Computer 31'");
        mvc.perform(as(mutation("/api/visits/" + waiting.id() + "/assign", 2), Role.LSR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resource.type").value("COMPUTER"));
        mvc.perform(post("/api/visits").contentType(MediaType.APPLICATION_JSON)
                .content("{\"visitType\":\"WALK_IN\",\"reason\":\"UI_ACCOUNT\",\"requestedResourceType\":\"PHONE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void needsPhoneHandoffReleasesComputerAndRecordsBothSessionsAndEvent() throws Exception {
        Visit visit = computer();
        JsonNode help = claim(raise(visit));
        closeHelp(help, "NEEDS_PHONE");
        var detail = visits.detail(visit.id());
        assertThat(detail.visit().status()).isEqualTo(Visit.Status.AT_RESOURCE);
        assertThat(detail.resource().label()).isEqualTo("Phone 01");
        assertThat(detail.requiredResourceType().name()).isEqualTo("PHONE");
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, visit.resourceId())).isEqualTo("FREE");
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ? AND end_reason = 'TO_PHONE'", visit.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ? AND ended_at IS NULL", visit.id())).isEqualTo(1);
        assertThat(detail.events().getLast().note()).isEqualTo("help request " + help.path("id").asLong() + ": needs phone");
        assertThat(detail.events().getLast().fromStatus()).isEqualTo(Visit.Status.AT_RESOURCE);
        assertThat(detail.events().getLast().toStatus()).isEqualTo(Visit.Status.AT_RESOURCE);
        assertThat(detail.visit().version()).isEqualTo(3);
    }

    @Test
    void needsPhoneWaitsWhenPhonesExhaustedThenAssignsFreedPhone() throws Exception {
        Visit visit = computer();
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE' AND label NOT LIKE 'OR-%'");
        closeHelp(claim(raise(visit)), "NEEDS_PHONE");
        assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
        assertThat(visits.list(Visit.Status.WAITING_RESOURCE).getFirst().requiredResourceType().name()).isEqualTo("PHONE");
        jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Phone 01'");
        assertThat(visits.assign(visit.id(), 3).resource().label()).isEqualTo("Phone 01");
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ?", visit.id())).isEqualTo(2);
    }

    @Test
    void duplicateRaiseClaimAndForeignOwnerCloseAreRejected() throws Exception {
        Visit visit = computer();
        JsonNode help = raise(visit);
        mvc.perform(as(mutation("/api/visits/" + visit.id() + "/help-requests", 2), Role.CLERK)).andExpect(status().isConflict());
        help = claim(help);
        String path = "/api/help-requests/" + help.path("id").asLong();
        mvc.perform(as(mutation(path + "/claim", 1), Role.LSR)).andExpect(status().isConflict());
        mvc.perform(asStaff(mutation(path + "/close", 1).content("{\"outcome\":\"RESOLVED_ONLINE\"}"), Role.LSR, 2))
                .andExpect(status().isForbidden());
        assertThat(count("SELECT count(*) FROM help_request WHERE closed_at IS NULL")).isEqualTo(1);
    }

    @Test
    void raiseRequiresVisitVersionAndDoesNotChangeIt() throws Exception {
        Visit visit = computer();
        String path = "/api/visits/" + visit.id() + "/help-requests";
        mvc.perform(as(post(path), Role.CLERK)).andExpect(status().is(428));
        mvc.perform(as(post(path).header("If-Match", "bad"), Role.CLERK)).andExpect(status().isBadRequest());
        mvc.perform(as(mutation(path, 1), Role.CLERK)).andExpect(status().is(412));
        raise(visit);
        assertThat(visits.detail(visit.id()).visit().version()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"close", "left"})
    void finishingVisitEndsSessionAndOpenHelp(String action) throws Exception {
        Visit visit = computer();
        raise(visit);
        mvc.perform(as(mutation("/api/visits/" + visit.id() + "/" + action, 2), Role.LSR)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT outcome FROM help_request WHERE visit_id = ?", String.class, visit.id())).isEqualTo("VISIT_ENDED");
        assertThat(jdbc.queryForObject("SELECT end_reason FROM resource_session WHERE visit_id = ?", String.class, visit.id())).isEqualTo(action.equals("close") ? "CLOSED" : "LEFT");
        assertThat(count("SELECT count(*) FROM resource_session WHERE ended_at IS NULL")).isZero();
    }

    @Test
    void openingRolloverExpiresHelpAndEndsSessions() throws Exception {
        at("2026-11-25");
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.COMPUTER_ACCESS, null);
        visits.identify(visit.id(), 1, 0);
        visit = visits.route(visit.id(), 1).visit();
        raise(visit);
        at("2026-11-30");
        days.resetResourcesForOpening();
        assertThat(jdbc.queryForObject("SELECT outcome FROM help_request", String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT end_reason FROM resource_session", String.class)).isEqualTo("ROLLOVER");
        assertThat(count("SELECT count(*) FROM resource WHERE status <> 'FREE' OR active_visit_id IS NOT NULL")).isZero();
    }

    @Test
    void phoneOverrideRequiresNoteAndVersionAndPerformsHandoff() throws Exception {
        Visit visit = computer();
        String path = "/api/visits/" + visit.id() + "/phone-override";
        mvc.perform(as(mutation(path, 2).content("{\"note\":\" \"}"), Role.CENTER_MANAGER)).andExpect(status().isBadRequest());
        mvc.perform(as(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Synthetic need\"}"), Role.CENTER_MANAGER)).andExpect(status().is(428));
        mvc.perform(as(mutation(path, 1).content("{\"note\":\"Synthetic need\"}"), Role.CENTER_MANAGER)).andExpect(status().is(412));
        mvc.perform(as(mutation(path, 2).content("{\"note\":\"Synthetic need\"}"), Role.CENTER_MANAGER))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.resource.label").value("Phone 01"));
        assertThat(jdbc.queryForObject("SELECT end_reason FROM resource_session WHERE ended_at IS NOT NULL", String.class)).isEqualTo("TO_PHONE");
        assertThat(visits.detail(visit.id()).events().getLast().note()).isEqualTo("phone override: Synthetic need");
    }

    @ParameterizedTest @EnumSource(Role.class)
    void phoneOverrideAllowsOnlySupervisorAndManager(Role role) throws Exception {
        Visit visit = computer();
        mvc.perform(as(mutation("/api/visits/" + visit.id() + "/phone-override", 2).content("{\"note\":\"Synthetic need\"}"), role))
                .andExpect(status().is(role == Role.SUPERVISING_LSR || role == Role.CENTER_MANAGER ? 200 : 403));
    }

    @Test
    void preCutoverPhoneAccessStillRoutesDirectlyToPhoneWithSession() {
        at("2026-11-25");
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.PHONE_ACCESS, null);
        visits.identify(visit.id(), 1, 0);
        assertThat(visits.route(visit.id(), 1).resource().label()).isEqualTo("Phone 01");
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ? AND ended_at IS NULL", visit.id())).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"2026-11-25", "2026-11-30"})
    void simulatorDrawsOnlyActiveReasons(String date) {
        when(clock.instant()).thenReturn(Instant.parse(date + "T21:39:00Z"));
        simulatorRepository.createDay(LocalDate.parse(date), 42, 20);
        var activeSettings = com.frontline.simulator.SimulatorTestSettings.settings(
                Map.of("PHONE_ACCESS", 70, "UI_ACCOUNT", 70), Map.of("MANDATORY", 1), 0);
        var engine = new com.frontline.simulator.SimulatorEngine(simulatorRepository, activeSettings,
                new com.frontline.simulator.SimulatorWork(simulatorRepository, activeSettings, visits), visits);
        var simulator = new TrafficSimulator(engine, simulatorRepository, activeSettings,
                new com.frontline.business.BusinessCalendar(clock, () -> java.util.Set.of()), clock);
        simulator.start(); simulator.tick();
        simulator.stop();
        var created = visits.list(null);
        assertThat(created).hasSize(20);
        assertThat(created).allMatch(item -> item.visit().reason() == (date.equals("2026-11-25") ? Visit.Reason.PHONE_ACCESS : Visit.Reason.UI_ACCOUNT));
    }

    @ParameterizedTest @ValueSource(strings = {"RESOLVED_ONLINE", "REFERRED_OUT"})
    void nonPhoneOutcomesLeaveVisitAndSessionUnchanged(String outcome) throws Exception {
        Visit visit = computer();
        closeHelp(claim(raise(visit)), outcome);
        assertThat(visits.detail(visit.id()).visit()).isEqualTo(visit);
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ? AND ended_at IS NULL", visit.id())).isEqualTo(1);
    }

    @Test
    void helpListIncludesVisitResourceRoomClaimAndAge() throws Exception {
        Visit visit = computer();
        claim(raise(visit));
        mvc.perform(as(get("/api/help-requests?businessDate=2026-11-30&open=true"), Role.CLERK))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].ticket").value(visit.ticket()))
                .andExpect(jsonPath("$[0].resourceLabel").value("Computer 01"))
                .andExpect(jsonPath("$[0].roomCode").value("RESOURCE_ROOM"))
                .andExpect(jsonPath("$[0].raisedByStaffId").value(6))
                .andExpect(jsonPath("$[0].claimedByStaffId").value(1))
                .andExpect(jsonPath("$[0].ageSeconds").isNumber());
    }

    @ParameterizedTest @EnumSource(Role.class)
    void helpEndpointsEnforceTheirRoleMatrix(Role role) throws Exception {
        Visit visit = computer();
        boolean reader = role != Role.ADMINISTRATOR;
        boolean helper = role == Role.LSR || role == Role.SUPERVISING_LSR || role == Role.CENTER_MANAGER;
        mvc.perform(as(get("/api/help-requests?businessDate=2026-11-30&open=true"), role)).andExpect(status().is(reader ? 200 : 403));
        mvc.perform(as(mutation("/api/visits/" + visit.id() + "/help-requests", 2), role)).andExpect(status().is(reader ? 201 : 403));
        long id = reader ? jdbc.queryForObject("SELECT id FROM help_request", Long.class) : raise(visit).path("id").asLong();
        mvc.perform(as(mutation("/api/help-requests/" + id + "/claim", 0), role)).andExpect(status().is(helper ? 200 : 403));
        if (!helper) mvc.perform(as(mutation("/api/help-requests/" + id + "/claim", 0), Role.LSR)).andExpect(status().isOk());
        mvc.perform(as(mutation("/api/help-requests/" + id + "/close", 1).content("{\"outcome\":\"RESOLVED_ONLINE\"}"), role))
                .andExpect(status().is(helper ? 200 : 403));
    }

    @Test
    void helpMutationsRequireHelpVersionsAndValidClaimedOutcome() throws Exception {
        JsonNode help = raise(computer());
        String path = "/api/help-requests/" + help.path("id").asLong();
        mvc.perform(as(post(path + "/claim"), Role.LSR)).andExpect(status().is(428));
        mvc.perform(as(post(path + "/claim").header("If-Match", "bad"), Role.LSR)).andExpect(status().isBadRequest());
        mvc.perform(as(mutation(path + "/claim", 9), Role.LSR)).andExpect(status().is(412));
        mvc.perform(as(mutation(path + "/close", 0).content("{\"outcome\":\"RESOLVED_ONLINE\"}"), Role.LSR)).andExpect(status().isConflict());
        claim(help);
        mvc.perform(as(post(path + "/close").contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RESOLVED_ONLINE\"}"), Role.LSR)).andExpect(status().is(428));
        mvc.perform(as(mutation(path + "/close", 0).content("{\"outcome\":\"RESOLVED_ONLINE\"}"), Role.LSR)).andExpect(status().is(412));
        mvc.perform(as(mutation(path + "/close", 1).content("{\"outcome\":\"EXPIRED\"}"), Role.LSR)).andExpect(status().isBadRequest());
    }

    @Test
    void newHelpEndpointsAreNeverAnonymous() throws Exception {
        Visit visit = computer();
        mvc.perform(get("/api/help-requests?businessDate=2026-11-30")).andExpect(status().isUnauthorized());
        mvc.perform(mutation("/api/visits/" + visit.id() + "/help-requests", 2)).andExpect(status().isUnauthorized());
        mvc.perform(mutation("/api/help-requests/1/claim", 0)).andExpect(status().isUnauthorized());
        mvc.perform(mutation("/api/help-requests/1/close", 0).content("{\"outcome\":\"RESOLVED_ONLINE\"}")).andExpect(status().isUnauthorized());
        mvc.perform(mutation("/api/visits/" + visit.id() + "/phone-override", 2).content("{\"note\":\"Synthetic\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionAndHelpUniquenessConstraintsRejectDuplicates() throws Exception {
        Visit visit = computer();
        JsonNode help = raise(visit);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO resource_session (visit_id, resource_id, business_date, started_at) VALUES (?, ?, DATE '2026-11-30', CURRENT_TIMESTAMP)",
                visit.id(), visit.resourceId())).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        long freeComputer = jdbc.queryForObject("SELECT id FROM resource WHERE label = 'Computer 31'", Long.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO resource_session (visit_id, resource_id, business_date, started_at) VALUES (?, ?, DATE '2026-11-30', CURRENT_TIMESTAMP)",
                visit.id(), freeComputer)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        Visit other = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT, null);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO resource_session (visit_id, resource_id, business_date, started_at) VALUES (?, ?, DATE '2026-11-30', CURRENT_TIMESTAMP)",
                other.id(), visit.resourceId())).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO help_request (visit_id, session_id, business_date, raised_at, raised_by_staff_id) VALUES (?, ?, DATE '2026-11-30', CURRENT_TIMESTAMP, 6)",
                visit.id(), help.path("sessionId").asLong())).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @ParameterizedTest @EnumSource(value = Role.class, names = {"SUPERVISING_LSR", "CENTER_MANAGER"})
    void supervisorAndManagerCanCloseAnotherClaimantsRequest(Role role) throws Exception {
        JsonNode help = claim(raise(computer()));
        mvc.perform(as(mutation("/api/help-requests/" + help.path("id").asLong() + "/close", 1)
                .content("{\"outcome\":\"RESOLVED_ONLINE\"}"), role)).andExpect(status().isOk());
    }

    @Test
    void failedHandoffEventRollsBackHelpSessionsOwnershipAndRequestedType() throws Exception {
        Visit visit = computer();
        JsonNode help = claim(raise(visit));
        long id = help.path("id").asLong();
        doThrow(new IllegalStateException("Synthetic event failure")).when(visitRepository)
                .recordTransition(any(), any(), eq("help request " + id + ": needs phone"));
        assertThatThrownBy(() -> helpRequests.close(id, 1, 1, "LSR", HelpRequest.Outcome.NEEDS_PHONE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(visits.detail(visit.id()).visit()).isEqualTo(visit);
        assertThat(count("SELECT count(*) FROM help_request WHERE id = ? AND closed_at IS NULL AND version = 1", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ? AND ended_at IS NULL", visit.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM resource_session WHERE visit_id = ?", visit.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, visit.resourceId())).isEqualTo("OCCUPIED");
        assertThat(count("SELECT count(*) FROM resource WHERE type = 'PHONE' AND status <> 'FREE'")).isZero();
    }
}
