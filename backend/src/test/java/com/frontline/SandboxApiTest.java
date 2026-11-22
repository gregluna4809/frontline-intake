package com.frontline;

import com.frontline.business.*;
import com.frontline.sandbox.*;
import com.frontline.simulator.*;
import com.frontline.model.Visit;
import com.frontline.help.HelpRequestService;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"frontline.sandbox.enabled=true","business.scheduling.enabled=false","simulator.tick-seconds=3600"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AuthenticatedApiTestConfiguration.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SandboxApiTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    @MockitoBean TaskScheduler scheduler;
    @TestBean(name="realClock") Clock real;
    static Clock real() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(REAL);
        return clock;
    }
    @Autowired @Qualifier("businessClock") Clock clock;
    @Autowired SandboxService service;
    @Autowired SandboxStateRepository state;
    @Autowired SandboxNightlyReset nightly;
    @Autowired TrafficSimulator simulator;
    @MockitoSpyBean SimulatorRepository sims;
    @Autowired SimulatorProperties settings;
    @Autowired BusinessCalendar calendar;
    @Autowired TicketSequenceService tickets;
    @Autowired BusinessDayService days;
    @Autowired com.frontline.visit.VisitService visits;
    @Autowired HelpRequestService helps;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient jdbcClient;
    @Autowired MockMvc mvc;
    private static final Instant REAL = Instant.parse("2026-11-25T15:00:00Z");
    private SandboxService.StateResponse startup;
    private SandboxService.SummaryResponse startupSummary;

    @BeforeAll void captureStartup() {
        startup = service.state();
        startupSummary = service.summary();
    }

    @BeforeEach void reset() {
        when(real.instant()).thenReturn(REAL);
        ((SandboxClock)clock).release();
        jdbc.update("UPDATE sandbox_state SET anchor_sandbox = NULL, anchor_real = NULL, day_started_at = NULL, warming = FALSE, speed = 5, scenario = 'SHIFT_TO_ONLINE'");
        simulator.stop(); jdbc.update("UPDATE sim_switch SET enabled = TRUE");
    }
    private SandboxService.DayInput input(String date, String time, int speed) {
        return new SandboxService.DayInput(SandboxService.Mode.DATE,LocalDate.parse(date),LocalTime.parse(time),speed,SimulatorProperties.Scenario.SHIFT_TO_ONLINE);
    }
    private long count(String sql) { return jdbc.queryForObject(sql,Long.class); }
    @ParameterizedTest @ValueSource(ints={1,5,10})
    void clockScalesRealElapsedTime(int speed) {
        Instant anchor=Instant.parse("2026-11-30T14:30:00Z"); state.finish(anchor,REAL,speed,SimulatorProperties.Scenario.SHIFT_TO_ONLINE);
        when(real.instant()).thenReturn(REAL.plusSeconds(120));
        assertThat(clock.instant()).isEqualTo(anchor.plusSeconds(120L*speed));
    }
    @Test void startWipesOperationalDataPreservesSeedsAndUsesPostCutoverReasons() {
        var old=visits.checkIn(Visit.VisitType.WALK_IN,Visit.Reason.PHONE_ACCESS,null);
        long customer=sims.customer("EN",false);
        var result=service.start(input("2026-11-30","09:30",5));
        assertThat(count("SELECT count(*) FROM visit WHERE id = "+old.id())).isZero();
        assertThat(count("SELECT count(*) FROM customer WHERE id = "+customer)).isZero();
        assertThat(count("SELECT count(*) FROM customer WHERE display_name LIKE 'Demo Customer %'")).isEqualTo(50);
        assertThat(count("SELECT count(*) FROM staff")).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM resource")).isEqualTo(51);
        assertThat(count("SELECT count(*) FROM visit WHERE business_date <> DATE '2026-11-30'")).isZero();
        assertThat(count("SELECT count(*) FROM visit WHERE reason = 'PHONE_ACCESS'")).isZero();
        assertThat(count("SELECT count(*) FROM visit WHERE reason = 'UI_ACCOUNT'")).isPositive();
        assertThat(count("SELECT count(*) FROM (SELECT visit_type, min(substring(ticket from 2)::int) AS first FROM visit GROUP BY visit_type) issued WHERE first <> 1")).isZero();
        assertThat(count("SELECT count(*) FROM visit WHERE created_at < TIMESTAMPTZ '2026-11-30 08:30 America/New_York' OR created_at > TIMESTAMPTZ '2026-11-30 09:30 America/New_York'")).isZero();
        assertThat(result.sandboxNow()).isEqualTo(Instant.parse("2026-11-30T14:30:00Z"));
        assertThat(simulator.status().running()).isTrue();
        assertThat(state.state().warming()).isFalse();
    }
    @Test void preCutoverWarmStartUsesPhoneAccessAndNoUiAccount() {
        service.start(input("2026-11-25","09:30",5));
        assertThat(count("SELECT count(*) FROM visit WHERE reason = 'UI_ACCOUNT'")).isZero();
        assertThat(count("SELECT count(*) FROM visit WHERE reason = 'PHONE_ACCESS'")).isPositive();
    }
    @Test void lateWarmStartFinishesWithinThirtySecondsAndMatchesSchedule() {
        long started=System.nanoTime(); service.start(input("2026-11-30","16:30",5));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(30));
        var day=sims.day(LocalDate.parse("2026-11-30")).orElseThrow();
        long expected=ArrivalSchedule.times(day,settings).stream().filter(time -> !time.isAfter(clock.instant())).count();
        assertThat(day.arrivalsSoFar()).isEqualTo(expected);
    }
    @Test void validationAndGlobalRateLimitAreEnforced() throws Exception {
        for (String body : java.util.List.of("{\"mode\":\"DATE\",\"date\":\"2026-11-26\"}","{\"mode\":\"DATE\",\"date\":\"2026-11-30\",\"time\":\"17:00\"}","{\"mode\":\"DATE\",\"date\":\"2026-11-30\",\"speed\":2}"))
            mvc.perform(post("/api/sandbox/day").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/sandbox/day").contentType("application/json").content("{\"mode\":\"DATE\",\"date\":\"2026-11-30\",\"time\":\"08:30\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/sandbox/day").contentType("application/json").content("{\"mode\":\"NEXT\"}")).andExpect(status().isTooManyRequests());
    }
    @Test void nextAndRandomUseRealDateAndConfiguredBusinessRange() {
        service.start(new SandboxService.DayInput(SandboxService.Mode.NEXT,null,LocalTime.of(8,30),5,null));
        assertThat(clock.instant().atZone(BusinessCalendar.ZONE).toLocalDate()).isEqualTo(LocalDate.parse("2026-11-30"));
        for(int n=0;n<8;n++) {
            service.start(new SandboxService.DayInput(SandboxService.Mode.RANDOM,null,LocalTime.of(8,30),5,null),true);
            var date=clock.instant().atZone(BusinessCalendar.ZONE).toLocalDate();
            assertThat(date).isBetween(LocalDate.parse("2026-11-02"),LocalDate.parse("2026-12-31"));
            assertThat(calendar.isBusinessDay(date)).isTrue();
        }
    }
    @Test void tenTimesTicksProcessEligibleTasksAndLeaveDisabledRrClaimPending() {
        service.start(input("2026-11-30","09:30",10));
        repositoryPauseArrivals(); repositoryPauseRr();
        Visit visit=visits.checkIn(Visit.VisitType.WALK_IN,Visit.Reason.UI_ACCOUNT,null);
        visits.identify(visit.id(),1,0); visit=visits.route(visit.id(),1).visit();
        var request=helps.raise(visit.id(),2,6);
        sims.schedule(visit.id(),"CLAIM_HELP",clock.instant(),null,request.sessionId(),null,request.id());
        for(int seconds=5;seconds<=60;seconds+=5) {
            when(real.instant()).thenReturn(REAL.plusSeconds(seconds)); simulator.tick();
            assertThat(count("SELECT count(*) FROM sim_task t JOIN visit v ON v.id=t.visit_id WHERE t.done_at IS NULL AND t.action NOT IN ('CLAIM_HELP') AND t.due_at < TIMESTAMPTZ '"+clock.instant().minusSeconds(50)+"'" )).isZero();
        }
        assertThat(count("SELECT count(*) FROM sim_task WHERE action='CLAIM_HELP' AND help_request_id="+request.id()+" AND done_at IS NULL")).isEqualTo(1);
    }
    private void repositoryPauseArrivals(){ sims.setSwitch("ARRIVALS",false); }
    private void repositoryPauseRr(){ sims.setSwitch("RR_LSRS",false); }
    @Test void businessTimestampsAndHelpAgeUseFiveTimesSandboxTime() {
        service.start(input("2026-11-30","08:30",5)); repositoryPauseArrivals(); repositoryPauseRr();
        Instant time=clock.instant(); Visit visit=visits.checkIn(Visit.VisitType.WALK_IN,Visit.Reason.UI_ACCOUNT,null);
        assertThat(visit.createdAt()).isEqualTo(time);
        visits.identify(visit.id(),1,0); visits.route(visit.id(),1);
        var help=helps.raise(visit.id(),2,6);
        for(int seconds=5;seconds<=120;seconds+=5){when(real.instant()).thenReturn(REAL.plusSeconds(seconds));simulator.tick();}
        assertThat(helps.list(LocalDate.parse("2026-11-30"),true).stream().filter(item->item.id()==help.id()).findFirst().orElseThrow().ageSeconds()).isEqualTo(600);
    }
    @Test void warmingPausesNormalTicksAndSchedulesAndStateIsAnonymous() throws Exception {
        long beforeDays=count("SELECT count(*) FROM sim_day");
        long beforeSnapshots=count("SELECT count(*) FROM business_day_snapshot");
        jdbc.update("UPDATE sandbox_state SET warming=TRUE");
        simulator.start(); simulator.tick();
        new BusinessSchedules(calendar,simulator,tickets,days).reconcileSimulator();
        assertThat(count("SELECT count(*) FROM sim_day")).isEqualTo(beforeDays);
        assertThat(count("SELECT count(*) FROM business_day_snapshot")).isEqualTo(beforeSnapshots);
        mvc.perform(get("/api/sandbox/state").header("Authorization","")).andExpect(status().isOk()).andExpect(jsonPath("$.warming").value(true));
        mvc.perform(get("/api/admin/staff")).andExpect(status().isForbidden());
        jdbc.update("UPDATE sandbox_state SET warming=FALSE"); simulator.stop();
    }
    @Test void nightlyResetBypassesRateLimitAndStartsNextAtTenFifteen() {
        service.start(input("2026-11-25","08:30",1)); nightly.reset();
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-30T15:15:00Z"));
        assertThat(state.state().speed()).isEqualTo(5);
        assertThat(state.state().scenario()).isEqualTo(SimulatorProperties.Scenario.SHIFT_TO_ONLINE);
    }

    @Test void startupWarmsTheNextBusinessDay() {
        assertThat(startup.sandboxNow()).isEqualTo(Instant.parse("2026-11-30T15:15:00Z"));
        assertThat(startup.dayStartedAt()).isEqualTo(REAL);
        assertThat(startup.warming()).isFalse();
        assertThat(startupSummary.arrivalsToday()).isPositive();
    }

    @Test void idleMinuteCheckWarmsTheNextBusinessDayAndMultipliesTheTarget() {
        service.reconcile();
        LocalDate date = LocalDate.parse("2026-11-30");
        var day = sims.day(date).orElseThrow();
        int drawn = settings.rangeFor(date).draw(new java.util.Random(day.seed()));
        assertThat(day.targetArrivals()).isEqualTo((int) Math.round(drawn * 1.4));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-30T15:15:00Z"));
        assertThat(day.arrivalsSoFar()).isPositive();
        assertThat(simulator.status().running()).isTrue();
    }

    @Test void minuteCheckRollsAt1530AndSkipsHolidaysAndWeekends() {
        service.start(new SandboxService.DayInput(SandboxService.Mode.DATE,
                LocalDate.parse("2026-11-25"), LocalTime.of(15, 29), 1,
                SimulatorProperties.Scenario.BEHAVIOR_UNCHANGED));
        service.reconcile();
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-25T20:29:00Z"));
        when(real.instant()).thenReturn(REAL.plusSeconds(60));
        service.reconcile();
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-30T15:15:00Z"));
        assertThat(state.state().scenario()).isEqualTo(SimulatorProperties.Scenario.BEHAVIOR_UNCHANGED);
        assertThat(state.state().speed()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM sim_day WHERE swept_at IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM business_day_snapshot WHERE business_date IN (DATE '2026-11-25', DATE '2026-11-30') AND jsonb_array_length(payload->'visits') > 0")).isZero();
    }

    @Test void minuteCheckRollsImmediatelyWhenClosed() {
        service.start(input("2026-11-30", "16:30", 5));
        when(real.instant()).thenReturn(REAL.plusSeconds(192));
        assertThat(calendar.isOpenNow()).isFalse();
        service.reconcile();
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-12-01T15:15:00Z"));
    }

    @Test void minuteCheckLeavesWarmingAloneAndRestartsStoppedSimulator() throws Exception {
        jdbc.update("UPDATE sandbox_state SET warming = TRUE");
        service.reconcile();
        assertThat(state.state().dayStartedAt()).isNull();
        assertThat(simulator.status().running()).isFalse();
        jdbc.update("UPDATE sandbox_state SET warming = FALSE");
        service.reconcile();
        simulator.stop();
        service.reconcile();
        assertThat(simulator.status().running()).isTrue();
        mvc.perform(post("/api/simulator/stop")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The sandbox simulator cannot be stopped."));
        assertThat(simulator.status().running()).isTrue();
    }

    @Test void fixedSeedWarmStartAtTenFifteenAndAnonymousSummaryCounts() throws Exception {
        for (String table : java.util.List.of("visit", "sim_task")) {
            jdbc.queryForObject("SELECT setval(pg_get_serial_sequence(?, 'id'), 1, false)", Long.class, table);
        }
        long seed = 42L;
        LocalDate date = LocalDate.parse("2026-11-30");
        int target = (int) Math.round(settings.rangeFor(date).draw(new java.util.Random(seed)) * 1.4);
        var seededRepository = new SimulatorRepository(jdbcClient);
        doAnswer(call -> seededRepository.createDay(date, seed, target))
                .when(sims).createDay(eq(date), anyLong(), anyInt());
        service.start(new SandboxService.DayInput(SandboxService.Mode.DATE, date, null, null, null));
        var summary = service.summary();
        System.out.println("S2.1 fixed seed 42 beingServed=" + summary.beingServed());
        assertThat(summary.beingServed()).isBetween(25L, 40L);
        assertThat(summary.sandboxNow()).isEqualTo(Instant.parse("2026-11-30T15:15:00Z"));
        long appointments = count("""
                SELECT count(*) FROM visit v WHERE visit_type = 'APPOINTMENT' AND status = 'WAITING_STAFF'
                  AND EXISTS (SELECT 1 FROM sim_task t WHERE t.visit_id = v.id
                              AND t.action = 'CLOSE_APPOINTMENT' AND t.done_at IS NULL)
                """);
        mvc.perform(get("/api/sandbox/summary").header("Authorization", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sandboxNow").value("2026-11-30T15:15:00Z"))
                .andExpect(jsonPath("$.beingServed").value(count("SELECT count(*) FROM visit WHERE visit_type = 'WALK_IN' AND status = 'AT_RESOURCE'") + appointments))
                .andExpect(jsonPath("$.waiting").value(count("SELECT count(*) FROM visit WHERE status IN ('WAITING_RESOURCE', 'WAITING_STAFF')")))
                .andExpect(jsonPath("$.openHelp").value(count("SELECT count(*) FROM help_request WHERE closed_at IS NULL")))
                .andExpect(jsonPath("$.arrivalsToday").value(count("SELECT count(*) FROM visit")));
    }

}
