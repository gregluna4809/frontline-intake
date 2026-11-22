package com.frontline;

import com.frontline.business.BusinessCalendar;
import com.frontline.business.BusinessDayService;
import com.frontline.help.HelpRequestRepository;
import com.frontline.help.HelpRequestService;
import com.frontline.model.HelpRequest;
import com.frontline.model.Visit;
import com.frontline.simulator.*;
import com.frontline.visit.VisitService;
import java.time.*;
import java.util.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"business.scheduling.enabled=false", "simulator.tick-seconds=3600",
        "simulator.leave-early-rate=0", "simulator.help-rate.UI_ACCOUNT=1",
        "simulator.help-rate.COMPUTER_ACCESS=1", "simulator.help-rate.JOB_SEARCH=1",
        "simulator.session-minutes.ui-account.min=45", "simulator.session-minutes.ui-account.max=45",
        "simulator.session-minutes.computer.min=60", "simulator.session-minutes.computer.max=60"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AuthenticatedApiTestConfiguration.class)
@Testcontainers
class SimulatorBotsTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean(name = "businessClock") Clock clock;
    @MockitoSpyBean SimulatorProperties settings;
    @Autowired SimulatorWork work;
    @Autowired SimulatorRepository repository;
    @Autowired TrafficSimulator simulator;
    @Autowired VisitService visits;
    @Autowired HelpRequestService helpService;
    @Autowired HelpRequestRepository helps;
    @Autowired BusinessDayService days;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private static final LocalDate DATE = LocalDate.parse("2026-11-30");

    @BeforeEach void reset() {
        at(DATE, "10:00"); simulator.stop();
        jdbc.execute("UPDATE resource SET active_visit_id = NULL, status = 'FREE'");
        jdbc.execute("DELETE FROM sim_task"); jdbc.execute("DELETE FROM help_request");
        jdbc.execute("DELETE FROM resource_session"); jdbc.execute("DELETE FROM visit_event"); jdbc.execute("DELETE FROM visit");
        jdbc.execute("TRUNCATE sim_day, business_day_rollover, business_day_snapshot, call_out");
        jdbc.execute("UPDATE staff SET active = TRUE");
        jdbc.execute("UPDATE sim_switch SET enabled = TRUE");
        jdbc.execute("ALTER TABLE visit ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE sim_task ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE resource_session ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE help_request ALTER COLUMN id RESTART WITH 1");
        repository.createDay(DATE, 42, 0);
        repository.setSwitch("ARRIVALS", false);
    }
    private void at(LocalDate date, String time) { when(clock.instant()).thenReturn(date.atTime(LocalTime.parse(time)).atZone(BusinessCalendar.ZONE).toInstant()); }
    private Instant now() { return clock.instant(); }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private void force(HelpRequest.Outcome outcome) {
        double phone = outcome == HelpRequest.Outcome.NEEDS_PHONE ? 1 : 0;
        double referred = outcome == HelpRequest.Outcome.REFERRED_OUT ? 1 : 0;
        doReturn(new SimulatorProperties.HelpOutcomes(new SimulatorProperties.OutcomeRates(phone,referred),
                new SimulatorProperties.OutcomeRates(phone,referred), new SimulatorProperties.OutcomeRates(0,0.10)))
                .when(settings).helpOutcomes();
    }
    private SimulatorRepository.Task task(long visit, String action) {
        long id = jdbc.queryForObject("SELECT id FROM sim_task WHERE visit_id = ? AND action = ? ORDER BY id DESC LIMIT 1", Long.class, visit, action);
        return repository.task(id).orElseThrow();
    }
    private void run(SimulatorRepository.Task task) { when(clock.instant()).thenReturn(task.dueAt()); work.task(task.id(), now()); }
    private Visit routed(Visit.VisitType type, Visit.Reason reason) {
        Visit visit = visits.checkIn(type, reason, type == Visit.VisitType.APPOINTMENT ? Visit.AppointmentCategory.MANDATORY : null, "SIMULATOR");
        repository.schedule(visit.id(), "IDENTIFY_AND_ROUTE", now(), 1L, null);
        work.task(task(visit.id(), "IDENTIFY_AND_ROUTE").id(), now());
        return visits.detail(visit.id()).visit();
    }
    private Visit computer() { return routed(Visit.VisitType.WALK_IN, Visit.Reason.UI_ACCOUNT); }
    private HelpRequest raise(Visit visit) {
        run(task(visit.id(), "RAISE_HELP"));
        return helps.list(visit.businessDate(), true).stream().filter(help -> help.visitId() == visit.id()).findFirst().orElseThrow();
    }
    private SimulatorRepository.Task claim(Visit visit) { run(task(visit.id(), "CLAIM_HELP")); return task(visit.id(), "FINISH_HELP"); }
    private long reserve(long visit, long staff) {
        repository.schedule(visit, "FINISH_HELP", now().plusSeconds(10000), null, null, staff, null);
        return task(visit, "FINISH_HELP").id();
    }

    @Test void forcedHelpDrawSchedulesAndRaisesAgainstTheComputerSessionAtExpectedTime() {
        Visit visit = computer();
        var helpTask = task(visit.id(), "RAISE_HELP");
        Random random = new Random(42 + visit.id() * 130363L);
        long length = settings.sessionMinutes().uiAccount().draw(random) * 60L;
        random.nextDouble(); random.nextDouble();
        long offset = Math.round(length * (0.10 + random.nextDouble() * 0.50));
        assertThat(helpTask.dueAt()).isEqualTo(now().plusSeconds(offset));
        assertThat(helpTask.resourceSessionId()).isEqualTo(repository.openSession(visit.id()).orElseThrow());
        repository.setSwitch("RR_LSRS", false);
        HelpRequest help = raise(visit);
        assertThat(help.sessionId()).isEqualTo(helpTask.resourceSessionId());
        assertThat(help.raisedByStaffId()).isEqualTo(6);
        assertThat(task(visit.id(), "CLAIM_HELP").dueAt()).isBetween(now().plusSeconds(60), now().plusSeconds(300));
    }
    @Test void resolvedOnlineUsesRrStaffReservationThenOriginalSessionEnds() {
        force(HelpRequest.Outcome.RESOLVED_ONLINE);
        Visit visit = computer(); var end = task(visit.id(), "END_SESSION");
        HelpRequest help = raise(visit); var finish = claim(visit);
        assertThat(finish.staffId()).isEqualTo(1);
        assertThat(repository.free(1)).isFalse();
        assertThat(finish.dueAt()).isBetween(now().plusSeconds(180), now().plusSeconds(600));
        run(finish);
        assertThat(helps.find(help.id()).orElseThrow().outcome()).isEqualTo(HelpRequest.Outcome.RESOLVED_ONLINE);
        assertThat(repository.free(1)).isTrue();
        assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.AT_RESOURCE);
        run(end);
        assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
        assertThat(jdbc.queryForObject("SELECT end_reason FROM resource_session", String.class)).isEqualTo("CLOSED");
        assertThat(count("SELECT count(*) FROM visit_event WHERE actor <> 'SIMULATOR'")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans = {false,true})
    void needsPhoneMovesOrWaitsAndSkipsOldComputerTask(boolean exhausted) {
        force(HelpRequest.Outcome.NEEDS_PHONE);
        Visit visit = computer(); var oldEnd = task(visit.id(), "END_SESSION");
        if (exhausted) jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'PHONE' AND label NOT LIKE 'OR-%'");
        HelpRequest help = raise(visit); run(claim(visit));
        var moved = visits.detail(visit.id());
        assertThat(helps.find(help.id()).orElseThrow().outcome()).isEqualTo(HelpRequest.Outcome.NEEDS_PHONE);
        assertThat(visits.detail(visit.id()).events().getLast().actor()).isEqualTo("SIMULATOR");
        assertThat(jdbc.queryForObject("SELECT end_reason FROM resource_session WHERE id = ?", String.class, oldEnd.resourceSessionId())).isEqualTo("TO_PHONE");
        assertThat(jdbc.queryForObject("SELECT status FROM resource WHERE id = ?", String.class, visit.resourceId())).isEqualTo("FREE");
        if (exhausted) {
            assertThat(moved.visit().status()).isEqualTo(Visit.Status.WAITING_RESOURCE);
            assertThat(task(visit.id(), "LEAVE_WAITING").doneAt()).isNull();
            jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Phone 01'");
            work.seat(visit.id(), now());
        } else assertThat(moved.resource().type().name()).isEqualTo("PHONE");
        var phoneEnd = task(visit.id(), "END_SESSION");
        assertThat(phoneEnd.resourceSessionId()).isNotEqualTo(oldEnd.resourceSessionId());
        assertThat(phoneEnd.dueAt()).isBetween(now().plusSeconds(15 * 60L), now().plusSeconds(30 * 60L));
        run(oldEnd); assertThat(repository.task(oldEnd.id()).orElseThrow().outcome()).isEqualTo("SKIPPED");
        assertThat(visits.detail(visit.id()).resource().type().name()).isEqualTo("PHONE");
    }
    @Test void referredOutSchedulesCloseWithinConfiguredLeaveRange() {
        force(HelpRequest.Outcome.REFERRED_OUT);
        Visit visit = computer(); var original = task(visit.id(), "END_SESSION");
        raise(visit); run(claim(visit));
        var leave = task(visit.id(), "END_SESSION");
        assertThat(leave.id()).isNotEqualTo(original.id());
        assertThat(leave.resourceSessionId()).isEqualTo(original.resourceSessionId());
        assertThat(leave.dueAt()).isBetween(now().plusSeconds(60), now().plusSeconds(300));
        run(leave); assertThat(visits.detail(visit.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
    }
    @Test void seededScenarioOutcomeSharesMatchThirtyAndSeventyPercentWithinTwoPoints() {
        for (var scenario : SimulatorProperties.Scenario.values()) {
            int phone = 0;
            for (long id = 1; id <= 10000; id++) {
                Random random = new Random(42 ^ id * 0x9E3779B97F4A7C15L ^ (id + 2) * 0xD1B54A32D192ED03L ^ 37);
                if (settings.helpOutcomes().draw(Visit.Reason.UI_ACCOUNT, scenario, random) == HelpRequest.Outcome.NEEDS_PHONE) phone++;
            }
            assertThat(phone / 10000.0).isCloseTo(scenario == SimulatorProperties.Scenario.SHIFT_TO_ONLINE ? 0.30 : 0.70, within(0.02));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"COMPUTER_ACCESS","JOB_SEARCH"})
    void computerAccessAndJobSearchNeverNeedPhone(String reason) {
        force(HelpRequest.Outcome.NEEDS_PHONE);
        Visit visit = routed(Visit.VisitType.WALK_IN, Visit.Reason.valueOf(reason));
        HelpRequest help = raise(visit); run(claim(visit));
        assertThat(helps.find(help.id()).orElseThrow().outcome()).isNotEqualTo(HelpRequest.Outcome.NEEDS_PHONE);
        assertThat(visits.detail(visit.id()).visit().requestedResourceType()).isNull();
        for (long id = 0; id < 1000; id++) {
            assertThat(settings.helpOutcomes().draw(Visit.Reason.valueOf(reason), settings.scenario(), new Random(42 + id)))
                    .isNotEqualTo(HelpRequest.Outcome.NEEDS_PHONE);
        }
    }
    @Test void rrOffLeavesClaimPendingAndHumanClaimMakesItSkipped() {
        Visit visit = computer(); HelpRequest help = raise(visit); var claim = task(visit.id(), "CLAIM_HELP");
        repository.setSwitch("RR_LSRS", false); run(claim);
        assertThat(repository.task(claim.id()).orElseThrow().doneAt()).isNull();
        repository.setSwitch("RR_LSRS", true); work.task(claim.id(), now());
        assertThat(helps.find(help.id()).orElseThrow().claimedByStaffId()).isEqualTo(1);
        Visit other = computer(); HelpRequest humanHelp = raise(other); var otherClaim = task(other.id(), "CLAIM_HELP");
        helpService.claim(humanHelp.id(), humanHelp.version(), 2);
        run(otherClaim); assertThat(repository.task(otherClaim.id()).orElseThrow().outcome()).isEqualTo("SKIPPED");
    }
    @Test void staffSelectionFallsBackThenKeepsPendingWhenEveryoneBusy() {
        Visit visit = computer(); raise(visit);
        reserve(visit.id(), 1);
        var finish = claim(visit); assertThat(finish.staffId()).isEqualTo(2);
        Visit other = computer(); raise(other);
        reserve(other.id(), 3); long last = reserve(other.id(), 4);
        var claim = task(other.id(), "CLAIM_HELP"); run(claim);
        assertThat(repository.task(claim.id()).orElseThrow().doneAt()).isNull();
        repository.done(last, now(), "DONE"); work.task(claim.id(), now());
        assertThat(task(other.id(), "FINISH_HELP").staffId()).isEqualTo(4);
    }
    @Test void callOutDutyAndActiveStaffDetermineCandidates() {
        Visit visit = computer(); raise(visit);
        jdbc.update("UPDATE staff SET active = FALSE WHERE id = 1");
        jdbc.update("INSERT INTO call_out (staff_id, date) VALUES (3, ?)", DATE);
        var finish = claim(visit); assertThat(finish.staffId()).isEqualTo(3);
    }
    @Test void switchOffMidHelpFinishesStartedWorkWithoutNewClaims() {
        force(HelpRequest.Outcome.RESOLVED_ONLINE);
        Visit first = computer(); HelpRequest firstHelp = raise(first); var finish = claim(first);
        Visit second = computer(); HelpRequest secondHelp = helpService.raise(second.id(), second.version(), 6);
        repository.schedule(second.id(), "CLAIM_HELP", finish.dueAt(), null, repository.openSession(second.id()).orElseThrow(), null, secondHelp.id());
        repository.setSwitch("RR_LSRS", false); when(clock.instant()).thenReturn(finish.dueAt());
        simulator.start(); simulator.tick(); simulator.stop();
        assertThat(helps.find(firstHelp.id()).orElseThrow().outcome()).isEqualTo(HelpRequest.Outcome.RESOLVED_ONLINE);
        assertThat(helps.find(secondHelp.id()).orElseThrow().claimedByStaffId()).isNull();
        assertThat(task(second.id(), "CLAIM_HELP").doneAt()).isNull();
    }
    @Test void noActiveClerkFinishesRaiseWithNoClerkWithoutRetry() {
        Visit visit = computer(); jdbc.update("UPDATE staff SET active = FALSE WHERE role = 'CLERK'");
        var task = task(visit.id(), "RAISE_HELP"); run(task);
        assertThat(repository.task(task.id()).orElseThrow().outcome()).isEqualTo("NO_CLERK");
        assertThat(repository.task(task.id()).orElseThrow().doneAt()).isNotNull();
        assertThat(helps.list(DATE, true)).isEmpty();
        jdbc.update("UPDATE staff SET active = TRUE WHERE role = 'CLERK'"); work.task(task.id(), now());
        assertThat(helps.list(DATE, true)).isEmpty();
    }
    @Test void unassignedAppointmentIsAssignedServedAndClosedByFreeAppointmentLsr() {
        Visit appointment = routed(Visit.VisitType.APPOINTMENT, null);
        work.serveAppointment(appointment.id(), now()); var close = task(appointment.id(), "CLOSE_APPOINTMENT");
        assertThat(close.staffId()).isEqualTo(2);
        assertThat(visits.detail(appointment.id()).visit().assignedStaffId()).isEqualTo(2);
        assertThat(close.dueAt()).isBetween(now().plusSeconds(20*60L), now().plusSeconds(40*60L));
        assertThat(repository.free(2)).isFalse();
        assertThat(visits.detail(appointment.id()).events().getLast().actor()).isEqualTo("SIMULATOR");
        run(close); assertThat(visits.detail(appointment.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
        assertThat(repository.free(2)).isTrue();
    }
    @Test void assignedAppointmentWaitsForItsLsrAndStartedWorkFinishesWhenSwitchedOff() {
        Visit appointment = routed(Visit.VisitType.APPOINTMENT, null);
        appointment = visits.assignStaff(appointment.id(), 3, appointment.version()).visit();
        long reservation = reserve(appointment.id(), 3);
        work.serveAppointment(appointment.id(), now()); assertThat(repository.hasAppointmentTask(appointment.id())).isFalse();
        repository.done(reservation, now(), "DONE"); repository.setSwitch("APPOINTMENT_LSRS", false);
        work.serveAppointment(appointment.id(), now()); assertThat(repository.hasAppointmentTask(appointment.id())).isFalse();
        repository.setSwitch("APPOINTMENT_LSRS", true); work.serveAppointment(appointment.id(), now());
        var close = task(appointment.id(), "CLOSE_APPOINTMENT"); assertThat(close.staffId()).isEqualTo(3);
        repository.setSwitch("APPOINTMENT_LSRS", false); run(close);
        assertThat(visits.detail(appointment.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
    }
    @ParameterizedTest @ValueSource(strings = {"reschedule","reassign","close"})
    void humanAppointmentActionMakesBotCloseSkipped(String action) {
        Visit appointment = routed(Visit.VisitType.APPOINTMENT, null);
        work.serveAppointment(appointment.id(), now()); var close = task(appointment.id(), "CLOSE_APPOINTMENT");
        Visit current = visits.detail(appointment.id()).visit();
        if (action.equals("reschedule")) visits.reschedule(current.id(), current.version());
        else if (action.equals("reassign")) visits.assignStaff(current.id(), 4, current.version());
        else visits.close(current.id(), current.version());
        run(close); assertThat(repository.task(close.id()).orElseThrow().outcome()).isEqualTo("SKIPPED");
    }
    @Test void appointmentSwitchOffPreventsStartingAndStaffIsNotDoubleBooked() {
        Visit first = routed(Visit.VisitType.APPOINTMENT, null);
        Visit second = routed(Visit.VisitType.APPOINTMENT, null);
        repository.setSwitch("APPOINTMENT_LSRS", false); simulator.start(); simulator.tick();
        assertThat(repository.hasAppointmentTask(first.id())).isFalse();
        repository.setSwitch("APPOINTMENT_LSRS", true); simulator.tick(); simulator.stop();
        assertThat(task(first.id(), "CLOSE_APPOINTMENT").staffId()).isEqualTo(2);
        assertThat(task(second.id(), "CLOSE_APPOINTMENT").staffId()).isEqualTo(3);
        simulator.start(); simulator.tick(); simulator.stop();
        assertThat(count("SELECT count(*) FROM sim_task WHERE action = 'CLOSE_APPOINTMENT'")).isEqualTo(2);
    }
    @Test void sweepEndsLateWaitingAndServingAppointmentsAndHelpWithoutNextDayFtr() {
        LocalDate before = LocalDate.parse("2026-11-25"); at(before,"10:00"); repository.createDay(before,42,0);
        Visit serving = routed(Visit.VisitType.APPOINTMENT, null); work.serveAppointment(serving.id(), now());
        Visit waiting = routed(Visit.VisitType.APPOINTMENT, null);
        Visit checked = visits.checkIn(Visit.VisitType.APPOINTMENT,null,Visit.AppointmentCategory.MANDATORY,"SIMULATOR");
        repository.schedule(checked.id(),"IDENTIFY_AND_ROUTE", before.atTime(16,43).atZone(BusinessCalendar.ZONE).toInstant(),1L,null);
        Visit identified = visits.checkIn(Visit.VisitType.APPOINTMENT,null,Visit.AppointmentCategory.MANDATORY,"SIMULATOR");
        visits.identify(identified.id(),1,0,"SIMULATOR");
        repository.schedule(identified.id(),"IDENTIFY_AND_ROUTE",before.atTime(16,43).atZone(BusinessCalendar.ZONE).toInstant(),1L,null);
        Visit computer = routed(Visit.VisitType.WALK_IN,Visit.Reason.COMPUTER_ACCESS); HelpRequest help = raise(computer);
        at(before,"16:40"); simulator.start(); simulator.tick(); simulator.stop();
        assertThat(visits.detail(serving.id()).visit().status()).isEqualTo(Visit.Status.CLOSED);
        for (Visit appointment : List.of(waiting,checked,identified)) assertThat(visits.detail(appointment.id()).visit().status()).isEqualTo(Visit.Status.LEFT);
        assertThat(helps.find(help.id()).orElseThrow().outcome()).isEqualTo(HelpRequest.Outcome.VISIT_ENDED);
        assertThat(repository.openTasks(before)).isZero();
        at(DATE,"08:30"); days.resetResourcesForOpening();
        assertThat(count("SELECT count(*) FROM visit WHERE appointment_status = 'NOT_ATTENDED_FTR'")).isZero();
    }
    @Test void statusIncludesScenarioAndSeparateHelpWaitingAndServingCounts() throws Exception {
        raise(computer()); routed(Visit.VisitType.APPOINTMENT,null);
        Visit serving = routed(Visit.VisitType.APPOINTMENT,null); work.serveAppointment(serving.id(),now());
        mvc.perform(get("/api/simulator/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("SHIFT_TO_ONLINE"))
                .andExpect(jsonPath("$.openHelpRequests").value(1))
                .andExpect(jsonPath("$.appointmentsWaiting").value(1))
                .andExpect(jsonPath("$.appointmentsBeingServed").value(1));
    }
    @Test void helpSettingsRejectInvalidRatesAndPhoneOutcomesForComputerAccess() {
        assertThatThrownBy(() -> new SimulatorProperties.OutcomeRates(0.9,0.2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SimulatorProperties.HelpOutcomes(new SimulatorProperties.OutcomeRates(0.3,0.05),
                new SimulatorProperties.OutcomeRates(0.7,0.05),new SimulatorProperties.OutcomeRates(0.1,0.1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void seatingSchedulesCustomerHelpWithoutClerkOrRrBotSwitchGatingTheRaise() {
        Visit visit = visits.checkIn(Visit.VisitType.WALK_IN,Visit.Reason.UI_ACCOUNT,null,"SIMULATOR");
        jdbc.update("UPDATE resource SET status = 'OCCUPIED' WHERE type = 'COMPUTER'");
        visits.identify(visit.id(),1,0,"SIMULATOR"); visits.route(visit.id(),1,"SIMULATOR");
        jdbc.update("UPDATE resource SET status = 'FREE' WHERE label = 'Computer 01'");
        repository.setSwitch("CLERKS",false);
        assertThat(work.seat(visit.id(),now())).isTrue();
        visit = visits.detail(visit.id()).visit();
        assertThat(task(visit.id(),"RAISE_HELP").resourceSessionId()).isEqualTo(repository.openSession(visit.id()).orElseThrow());
        repository.setSwitch("RR_LSRS",false);
        assertThat(raise(visit).raisedByStaffId()).isEqualTo(6);
    }
    @Test void raiseSkipsWhenHumanAlreadyRequestedHelpOrEndedTheSession() {
        Visit first = computer(); helpService.raise(first.id(),first.version(),6);
        var duplicate = task(first.id(),"RAISE_HELP"); run(duplicate);
        assertThat(repository.task(duplicate.id()).orElseThrow().outcome()).isEqualTo("SKIPPED");
        assertThat(count("SELECT count(*) FROM help_request")).isEqualTo(1);
        Visit second = computer(); visits.close(second.id(),second.version());
        var ended = task(second.id(),"RAISE_HELP"); run(ended);
        assertThat(repository.task(ended.id()).orElseThrow().outcome()).isEqualTo("SKIPPED");
    }
    @Test void appointmentsWaitForFreeAppointmentDutyStaffInOldestOrder() {
        Visit first = routed(Visit.VisitType.APPOINTMENT,null);
        Visit second = routed(Visit.VisitType.APPOINTMENT,null);
        reserve(first.id(),2); long release = reserve(first.id(),3); reserve(first.id(),4);
        simulator.start(); simulator.tick(); simulator.stop();
        assertThat(repository.hasAppointmentTask(first.id())).isFalse();
        assertThat(repository.hasAppointmentTask(second.id())).isFalse();
        repository.done(release,now(),"DONE");
        simulator.start(); simulator.tick(); simulator.stop();
        assertThat(task(first.id(),"CLOSE_APPOINTMENT").staffId()).isEqualTo(3);
        assertThat(repository.hasAppointmentTask(second.id())).isFalse();
    }
}
