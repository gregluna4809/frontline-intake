package com.frontline;

import com.frontline.business.BusinessCalendar;
import com.frontline.model.Visit;
import com.frontline.model.Staff.Role;
import com.frontline.simulator.*;
import com.frontline.visit.VisitService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
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

@SpringBootTest(properties = {"business.scheduling.enabled=false", "simulator.leave-early-rate=0", "simulator.weights.APPOINTMENT=0", "simulator.tick-seconds=3600"})
@AutoConfigureMockMvc
@Testcontainers
class TrafficSimulatorTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @Autowired TrafficSimulator simulator;
    @Autowired SimulatorRepository repository;
    @Autowired SimulatorProperties settings;
    @Autowired SimulatorWork work;
    @Autowired VisitService visits;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    private static final LocalDate DATE = LocalDate.parse("2026-11-30");

    @BeforeEach void reset() {
        at(DATE, "08:30"); simulator.stop();
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM sim_task"); jdbc.execute("DELETE FROM help_request");
        jdbc.execute("DELETE FROM resource_session"); jdbc.execute("DELETE FROM visit_event"); jdbc.execute("DELETE FROM visit");
        jdbc.execute("DELETE FROM customer WHERE display_name LIKE 'Sim Customer %'");
        jdbc.execute("TRUNCATE sim_day, business_day_rollover, business_day_snapshot");
        jdbc.execute("UPDATE sim_switch SET enabled = TRUE");
        jdbc.execute("ALTER SEQUENCE sim_customer_sequence RESTART WITH 1");
        jdbc.execute("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 1");
        jdbc.execute("ALTER SEQUENCE appointment_ticket_sequence RESTART WITH 1");
        jdbc.execute("ALTER TABLE visit ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE sim_task ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE resource_session ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE customer ALTER COLUMN id RESTART WITH 51");
    }
    private void at(LocalDate date, String time) { when(clock.instant()).thenReturn(date.atTime(LocalTime.parse(time)).atZone(BusinessCalendar.ZONE).toInstant()); }
    private Instant now() { return clock.instant(); }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private SimulatorRepository.Day seed(int target) { return repository.createDay(DATE, 42, target); }
    private Visit customerAtComputer() {
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT, null);
        visits.identify(visit.id(), 1, 0); return visits.route(visit.id(), 1).visit();
    }
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Role role) {
        long id = Map.of(Role.LSR, 1L, Role.SUPERVISING_LSR, 5L, Role.CLERK, 6L, Role.CENTER_MANAGER, 9L, Role.ADMINISTRATOR, 10L).get(role);
        var time = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("frontline").subject("staff" + id).issuedAt(time).expiresAt(time.plusSeconds(300))
                .claim("staffId", id).claim("role", role.name()).build();
        return request.header("Authorization", "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
    }
    @Test void sameSeedAndDateReproduceScheduleCustomersAndTasks() {
        var day = seed(30);
        var times = ArrivalSchedule.times(day, settings);
        simulator.start(); at(DATE, "10:30"); simulator.tick(); at(DATE, "10:35"); simulator.tick(); simulator.stop();
        var customers = jdbc.queryForList("SELECT display_name, language, veteran FROM customer WHERE display_name LIKE 'Sim Customer %' ORDER BY id");
        var tasks = jdbc.queryForList("SELECT visit_id, action, due_at, customer_id, resource_session_id, outcome FROM sim_task ORDER BY id");
        var arrivals = jdbc.queryForList("SELECT ticket, reason, appointment_category, business_date, status FROM visit ORDER BY id");
        reset(); seed(30); simulator.start(); at(DATE, "10:30"); simulator.tick(); at(DATE, "10:35"); simulator.tick(); simulator.stop();
        assertThat(ArrivalSchedule.times(repository.day(DATE).orElseThrow(), settings)).isEqualTo(times);
        assertThat(jdbc.queryForList("SELECT display_name, language, veteran FROM customer WHERE display_name LIKE 'Sim Customer %' ORDER BY id")).isEqualTo(customers);
        assertThat(jdbc.queryForList("SELECT visit_id, action, due_at, customer_id, resource_session_id, outcome FROM sim_task ORDER BY id")).isEqualTo(tasks);
        assertThat(jdbc.queryForList("SELECT ticket, reason, appointment_category, business_date, status FROM visit ORDER BY id")).isEqualTo(arrivals);
    }
    @Test void fullDayHitsTargetExactlyWithWeightedPeakSlots() {
        int target = settings.rangeFor(DATE).draw(new Random(42));
        var day = repository.createDay(DATE, 42, target);
        var schedule = ArrivalSchedule.times(day, settings);
        long peak = schedule.stream().map(time -> time.atZone(BusinessCalendar.ZONE).toLocalTime())
                .filter(time -> (!time.isBefore(LocalTime.of(9,0)) && time.isBefore(LocalTime.of(11,0)))
                    || (!time.isBefore(LocalTime.of(13,0)) && time.isBefore(LocalTime.of(15,30)))).count();
        long midday = schedule.stream().map(time -> time.atZone(BusinessCalendar.ZONE).toLocalTime())
                .filter(time -> !time.isBefore(LocalTime.of(11,0)) && time.isBefore(LocalTime.of(13,0))).count();
        assertThat(peak / 9.0).isGreaterThanOrEqualTo(2.5 * midday / 4.0);
        simulator.start();
        for (int minute = 0; minute <= 490; minute += 5) {
            at(DATE, LocalTime.of(8,30).plusMinutes(minute).toString()); simulator.tick();
        }
        simulator.stop();
        assertThat(repository.day(DATE).orElseThrow().arrivalsSoFar()).isEqualTo(target);
        assertThat(count("SELECT count(*) FROM visit")).isEqualTo(target);
        assertThat(count("SELECT count(*) FROM visit_event WHERE actor <> 'SIMULATOR'")).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"2026-11-25", "2026-11-30"})
    void dailyTargetUsesEffectiveRange(String date) {
        LocalDate day = LocalDate.parse(date); at(day, "08:30"); simulator.start(); simulator.tick(); simulator.stop();
        var saved = repository.day(day).orElseThrow();
        var range = settings.rangeFor(day);
        assertThat(saved.targetArrivals()).isBetween(range.min(), range.max());
        assertThat(saved.targetArrivals()).isEqualTo(range.draw(new Random(saved.seed())));
    }
    @Test void clerkRoutesAndSessionTaskClosesVisitAndSession() {
        seed(1); at(DATE, "10:00");
        assertThat(work.arrive(DATE, 0, now())).isTrue();
        var identify = repository.due(DATE, now().plusSeconds(240)).getFirst();
        at(DATE, "10:04"); work.task(identify.id(), now());
        Visit visit = visits.detail(identify.visitId()).visit();
        assertThat(visit.visitType()).isEqualTo(Visit.VisitType.WALK_IN);
        assertThat(visit.status()).isEqualTo(Visit.Status.AT_RESOURCE);
        var end = jdbc.queryForObject("SELECT id FROM sim_task WHERE action = 'END_SESSION'", Long.class);
        at(DATE, "11:05"); work.task(end, now());
        assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
        assertThat(jdbc.queryForObject("SELECT end_reason FROM resource_session", String.class)).isEqualTo("CLOSED");
    }
    @Test void clerksSwitchKeepsTasksPendingUntilEnabled() {
        seed(1); at(DATE, "10:00"); work.arrive(DATE, 0, now());
        repository.setSwitch("ARRIVALS", false); repository.setSwitch("CLERKS", false);
        simulator.start(); at(DATE, "10:05"); simulator.tick();
        assertThat(count("SELECT count(*) FROM sim_task WHERE done_at IS NULL AND action = 'IDENTIFY_AND_ROUTE'")).isEqualTo(1);
        repository.setSwitch("CLERKS", true); simulator.tick(); simulator.stop();
        assertThat(count("SELECT count(*) FROM sim_task WHERE done_at IS NULL AND action = 'IDENTIFY_AND_ROUTE'")).isZero();
    }
    @Test void humanClosureMakesSessionTaskSkipped() {
        seed(0); at(DATE, "10:00"); Visit visit = customerAtComputer();
        long session = repository.openSession(visit.id()).orElseThrow();
        repository.schedule(visit.id(), "END_SESSION", now(), null, session);
        visits.close(visit.id(), visit.version()); simulator.start(); simulator.tick(); simulator.stop();
        assertThat(jdbc.queryForObject("SELECT outcome FROM sim_task", String.class)).isEqualTo("SKIPPED");
    }
    @Test void waitingSeatingRespectsSwitchAndUnservedCustomerLeaves() {
        seed(0); at(DATE, "10:00");
        // Initialize opening before occupying the inventory.
        visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'COMPUTER'");
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT, null);
        visits.identify(visit.id(), 1, 0); visit = visits.route(visit.id(), 1).visit();
        repository.schedule(visit.id(), "LEAVE_WAITING", now().plusSeconds(3600), null, null);
        repository.setSwitch("RR_LSRS", false); jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Computer 01'");
        simulator.start(); simulator.tick(); assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
        repository.setSwitch("RR_LSRS", true); simulator.tick(); simulator.stop();
        assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.AT_RESOURCE);
        assertThat(count("SELECT count(*) FROM sim_task WHERE action = 'LEAVE_WAITING' AND outcome = 'SKIPPED'")).isEqualTo(1);
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'COMPUTER'");
        Visit waiter = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT, null);
        visits.identify(waiter.id(), 1, 0); visits.route(waiter.id(), 1);
        repository.schedule(waiter.id(), "LEAVE_WAITING", now(), null, null);
        simulator.start(); simulator.tick(); simulator.stop();
        assertThat(visits.detail(waiter.id()).visit().status()).isEqualTo(Visit.Status.LEFT);
    }
    @Test void sweepAt1640EndsOnlyEngineWalkInsAndMarksTasksClosedDay() {
        seed(0); at(DATE, "10:00"); Visit occupied = customerAtComputer();
        repository.schedule(occupied.id(), "IDENTIFY_AND_ROUTE", now(), 1L, null);
        Visit waiting = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
        repository.schedule(waiting.id(), "IDENTIFY_AND_ROUTE", now(), 1L, null);
        Visit appointment = visits.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY);
        repository.schedule(appointment.id(), "IDENTIFY_AND_ROUTE", now(), 1L, null);
        Visit human = visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.OTHER, null);
        simulator.start(); at(DATE, "16:40"); simulator.tick();
        assertThat(visits.detail(occupied.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
        assertThat(visits.detail(waiting.id()).visit().status()).isEqualTo(Visit.Status.LEFT);
        assertThat(visits.detail(appointment.id()).visit().status()).isEqualTo(Visit.Status.LEFT);
        assertThat(visits.detail(human.id()).visit()).isEqualTo(human);
        assertThat(repository.day(DATE).orElseThrow().sweptAt()).isEqualTo(now());
        assertThat(count("SELECT count(*) FROM sim_task WHERE outcome = 'CLOSED_DAY'")).isEqualTo(3);
        simulator.tick(); simulator.stop();
    }
    @Test void stoppedClosedWeekendAndHolidayTicksDoNothing() {
        at(DATE, "10:00"); simulator.tick(); assertThat(count("SELECT count(*) FROM sim_day")).isZero();
        simulator.start();
        for (String date : List.of("2026-11-28", "2026-11-26", "2026-11-27")) { at(LocalDate.parse(date), "10:00"); simulator.tick(); }
        at(DATE, "08:29"); simulator.tick(); at(DATE, "16:45"); simulator.tick(); simulator.stop();
        assertThat(count("SELECT count(*) FROM sim_day")).isZero(); assertThat(count("SELECT count(*) FROM sim_task")).isZero();
    }
    @Test void afterCutoverActiveReasonsAndPhoneGateHoldThroughFullLifecycle() {
        seed(60); simulator.start();
        for (int minute = 0; minute <= 490; minute += 10) { at(DATE, LocalTime.of(8,30).plusMinutes(minute).toString()); simulator.tick(); }
        simulator.stop();
        assertThat(count("SELECT count(*) FROM visit WHERE reason = 'PHONE_ACCESS'")).isZero();
        assertThat(count("SELECT count(*) FROM resource_session s JOIN resource r ON r.id = s.resource_id WHERE r.type = 'PHONE'")).isZero();
        assertThat(count("SELECT count(*) FROM resource_session s JOIN visit v ON v.id = s.visit_id JOIN resource r ON r.id = s.resource_id WHERE v.reason = 'UI_ACCOUNT' AND r.type = 'COMPUTER'")).isPositive();
    }
    @ParameterizedTest @EnumSource(Role.class)
    void switchEndpointsEnforceRoles(Role role) throws Exception {
        mvc.perform(as(get("/api/simulator/switches"), role)).andExpect(status().is(role == Role.CENTER_MANAGER || role == Role.ADMINISTRATOR || role == Role.SUPERVISING_LSR ? 200 : 403));
        mvc.perform(as(put("/api/simulator/switches/ARRIVALS").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"), role))
                .andExpect(status().is(role == Role.CENTER_MANAGER || role == Role.ADMINISTRATOR ? 200 : 403));
    }
    @Test void statusEndpointsAndSwitchValidation() throws Exception {
        mvc.perform(get("/api/simulator/switches")).andExpect(status().isUnauthorized());
        mvc.perform(as(put("/api/simulator/switches/ARRIVALS").contentType(MediaType.APPLICATION_JSON).content("{}"), Role.CENTER_MANAGER)).andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/simulator/switches/UNKNOWN").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"), Role.ADMINISTRATOR)).andExpect(status().isNotFound());
        seed(12); mvc.perform(as(get("/api/simulator/status"), Role.CENTER_MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.targetArrivals").value(12)).andExpect(jsonPath("$.arrivalsSoFar").value(0)).andExpect(jsonPath("$.openTaskCount").value(0));
        mvc.perform(as(post("/api/simulator/start"), Role.CENTER_MANAGER)).andExpect(status().isOk()).andExpect(jsonPath("$.running").value(true));
        mvc.perform(as(post("/api/simulator/stop"), Role.CENTER_MANAGER)).andExpect(status().isOk()).andExpect(jsonPath("$.running").value(false));
    }
    @Test void settingsValidateWeightsRangesCurveAndTiming() {
        assertThatThrownBy(() -> SimulatorTestSettings.settings(Map.of("OTHER", 0), Map.of("MANDATORY",1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimulatorTestSettings.settings(Map.of("UNKNOWN",1), Map.of("MANDATORY",1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SimulatorTestSettings.settings(Map.of("OTHER",1), Map.of("MANDATORY",1), 1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulatorProperties.Range(5,4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulatorProperties.DailyRange(DATE, -1,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulatorProperties(false, 4, settings.weights(), settings.appointmentWeights(), settings.dailyArrivals(), Map.of(), settings.clerkDelayMinutes(), settings.sessionMinutes(), 0,5,
                settings.scenario(), settings.helpRate(), settings.helpOutcomes(), settings.helpClaimDelayMinutes(), settings.helpWorkMinutes(), settings.appointmentMinutes(), settings.referredOutLeaveMinutes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulatorProperties(false, 4, settings.weights(), settings.appointmentWeights(), settings.dailyArrivals(), settings.curve(), settings.clerkDelayMinutes(), settings.sessionMinutes(), 0,0,
                settings.scenario(), settings.helpRate(), settings.helpOutcomes(), settings.helpClaimDelayMinutes(), settings.helpWorkMinutes(), settings.appointmentMinutes(), settings.referredOutLeaveMinutes())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void clerkQueuesUnservedWalkInWithTimedDeparture() {
        seed(1); at(DATE, "10:00"); work.arrive(DATE, 0, now());
        // Preserve occupied fixtures across the first service mutation's opening reconciliation.
        jdbc.update("INSERT INTO business_day_rollover (business_date) VALUES (?) ON CONFLICT DO NOTHING", DATE);
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'COMPUTER'");
        at(DATE, "10:04"); work.task(jdbc.queryForObject("SELECT id FROM sim_task", Long.class), now());
        var task = repository.due(DATE, now().plusSeconds(90 * 60L)).stream().filter(item -> item.action().equals("LEAVE_WAITING")).findFirst().orElseThrow();
        assertThat(task.dueAt()).isBetween(now().plusSeconds(45 * 60L), now().plusSeconds(90 * 60L));
        repository.setSwitch("RR_LSRS", false); when(clock.instant()).thenReturn(task.dueAt());
        simulator.start(); simulator.tick(); simulator.stop();
        assertThat(visits.detail(task.visitId()).visit().status()).isEqualTo(Visit.Status.LEFT);
    }
    @Test void earlyDepartureChecksItsOriginalSessionBeforeActing() {
        seed(0); at(DATE, "10:00"); Visit visit = customerAtComputer();
        long session = repository.openSession(visit.id()).orElseThrow();
        repository.schedule(visit.id(), "LEAVE_EARLY", now(), null, session);
        Visit moved = visits.phoneOverride(visit.id(), visit.version(), "Synthetic human decision").visit();
        simulator.start(); simulator.tick(); simulator.stop();
        assertThat(jdbc.queryForObject("SELECT outcome FROM sim_task", String.class)).isEqualTo("SKIPPED");
        assertThat(visits.detail(visit.id()).visit()).isEqualTo(moved);
        assertThat(repository.openSession(visit.id()).orElseThrow()).isNotEqualTo(session);
    }
    @Test void clerkRoutesAppointmentsWithoutServingThem() {
        seed(0); at(DATE, "10:00");
        Visit visit = visits.checkIn(Visit.VisitType.APPOINTMENT, null, Visit.AppointmentCategory.MANDATORY, "SIMULATOR");
        repository.schedule(visit.id(), "IDENTIFY_AND_ROUTE", now(), 1L, null);
        work.task(jdbc.queryForObject("SELECT id FROM sim_task", Long.class), now());
        assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.WAITING_STAFF);
        assertThat(repository.openTasks(DATE)).isZero();
        assertThat(count("SELECT count(*) FROM resource_session")).isZero();
    }
    @Test void arrivalsSwitchPausesCreationAndResumesDueSchedule() {
        seed(10); repository.setSwitch("ARRIVALS", false); simulator.start(); at(DATE, "16:39"); simulator.tick();
        assertThat(repository.day(DATE).orElseThrow().arrivalsSoFar()).isZero();
        repository.setSwitch("ARRIVALS", true); simulator.tick(); simulator.stop();
        assertThat(repository.day(DATE).orElseThrow().arrivalsSoFar()).isEqualTo(10);
    }
}
