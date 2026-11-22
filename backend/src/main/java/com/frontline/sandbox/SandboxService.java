package com.frontline.sandbox;

import com.frontline.business.*;
import com.frontline.simulator.*;
import com.frontline.simulator.SimulatorProperties.Scenario;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SandboxService {
    private final SandboxProperties properties;
    private final SandboxStateRepository state;
    private final SandboxPreparation preparation;
    private final Clock real;
    private final Clock clock;
    private final BusinessCalendar calendar;
    private final TicketSequenceService tickets;
    private final SimulatorEngine engine;
    private final SimulatorRepository simulatorRepository;
    private final TrafficSimulator simulator;
    private final JdbcClient jdbc;
    public SandboxService(SandboxProperties properties, SandboxStateRepository state, SandboxPreparation preparation,
            @Qualifier("realClock") Clock realClock, @Qualifier("businessClock") Clock businessClock,
            BusinessCalendar calendar, TicketSequenceService tickets, SimulatorEngine engine,
            SimulatorRepository simulatorRepository, TrafficSimulator simulator,
            JdbcClient jdbc) {
        this.properties = properties; this.state = state; this.preparation = preparation; this.real = realClock;
        this.clock = businessClock; this.calendar = calendar; this.tickets = tickets; this.engine = engine;
        this.simulatorRepository = simulatorRepository; this.simulator = simulator; this.jdbc = jdbc;
    }
    public StateResponse state() {
        requireEnabled(); var value = state.state();
        return new StateResponse(true,clock.instant(),value.speed(),value.scenario(),value.warming(),value.dayStartedAt());
    }
    public void reconcile() {
        if (!properties.enabled() || simulator.javaEngineDisabled() || state.state().warming()) return;
        synchronized (simulator) {
            var current = state.state();
            if (current.warming()) return;
            var now = clock.instant().atZone(BusinessCalendar.ZONE);
            boolean idle = current.dayStartedAt() == null;
            if (idle || !now.toLocalTime().isBefore(properties.rollAt()) || !calendar.isOpenNow()) {
                LocalDate base = idle ? real.instant().atZone(BusinessCalendar.ZONE).toLocalDate() : now.toLocalDate();
                start(new DayInput(Mode.NEXT, null, null, 5, current.scenario()), true, base);
            } else if (!simulator.status().running()) {
                simulator.start();
            }
        }
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SummaryResponse summary() {
        requireEnabled();
        Instant now = clock.instant();
        LocalDate date = now.atZone(BusinessCalendar.ZONE).toLocalDate();
        var counts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE visit_type = 'WALK_IN' AND status = 'AT_RESOURCE') AS seated,
                       count(*) FILTER (WHERE status IN ('WAITING_RESOURCE', 'WAITING_STAFF')) AS waiting,
                       count(*) AS arrivals
                FROM visit WHERE business_date = :date
                """).param("date", date).query((rs, row) ->
                        new VisitCounts(rs.getLong("seated"), rs.getLong("waiting"), rs.getLong("arrivals"))).single();
        return new SummaryResponse(now, counts.seated() + simulatorRepository.appointmentsBeingServed(date),
                counts.waiting(), simulatorRepository.openHelp(date), counts.arrivals());
    }
    private record VisitCounts(long seated, long waiting, long arrivals) {}
    public record SummaryResponse(Instant sandboxNow, long beingServed, long waiting, long openHelp, long arrivalsToday) {}
    public DayResponse start(DayInput input) { return start(input,false); }
    public DayResponse start(DayInput input, boolean bypassRateLimit) {
        return start(input, bypassRateLimit, null);
    }
    private DayResponse start(DayInput input, boolean bypassRateLimit, LocalDate nextAfter) {
        requireEnabled();
        simulator.requireJavaEngine();
        if (input == null || input.mode() == null) throw bad("Choose NEXT, RANDOM or DATE.");
        LocalTime time = input.time() == null ? properties.autoStartTime() : input.time();
        int speed = input.speed() == null ? 5 : input.speed();
        Scenario scenario = input.scenario() == null ? Scenario.SHIFT_TO_ONLINE : input.scenario();
        if (time.isBefore(LocalTime.of(8,30)) || time.isAfter(LocalTime.of(16,30)) || time.getSecond() != 0 || time.getNano() != 0) throw bad("Time must be 08:30–16:30 in whole minutes.");
        if (!Set.of(1,5,10).contains(speed)) throw bad("Speed must be 1, 5 or 10.");
        LocalDate date = chooseDate(input, nextAfter);
        Instant opening = date.atTime(8,30).atZone(BusinessCalendar.ZONE).toInstant();
        Instant target = date.atTime(time).atZone(BusinessCalendar.ZONE).toInstant();
        synchronized (simulator) {
            preparation.prepare(opening,real.instant(),speed,scenario,bypassRateLimit);
            SandboxClock sandboxClock = (SandboxClock) clock;
            sandboxClock.drive(opening);
            try {
                tickets.resetForOpening();
                for (Instant step = opening; !step.isAfter(target); step = step.plusSeconds(60)) {
                    sandboxClock.drive(step); engine.tick(date,step);
                }
                state.finish(target,real.instant(),speed,scenario); sandboxClock.release(); simulator.start();
                var day = simulatorRepository.day(date).orElseThrow();
                return new DayResponse(clock.instant(),speed,scenario,day.arrivalsSoFar(),day.targetArrivals());
            } catch (RuntimeException exception) {
                state.finish(clock.instant(),real.instant(),speed,scenario); sandboxClock.release(); simulator.stop(); throw exception;
            }
        }
    }
    private LocalDate chooseDate(DayInput input, LocalDate nextAfter) {
        LocalDate date;
        switch (input.mode()) {
            case DATE -> { date = input.date(); if (date == null) throw bad("DATE requires a date."); }
            case NEXT -> {
                date = (nextAfter == null ? real.instant().atZone(BusinessCalendar.ZONE).toLocalDate() : nextAfter).plusDays(1);
                while (!calendar.isBusinessDay(date)) date = date.plusDays(1);
            }
            case RANDOM -> {
                List<LocalDate> dates = properties.randomFrom().datesUntil(properties.randomTo().plusDays(1)).filter(calendar::isBusinessDay).toList();
                if (dates.isEmpty()) throw bad("Random range has no business days.");
                date = dates.get(new Random().nextInt(dates.size()));
            }
            default -> throw bad("Unknown sandbox mode.");
        }
        if (!calendar.isBusinessDay(date)) throw bad("Choose a business day.");
        return date;
    }
    private void requireEnabled() { if (!properties.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private ResponseStatusException bad(String detail) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,detail); }
    public enum Mode { NEXT, RANDOM, DATE }
    public record DayInput(Mode mode, LocalDate date, LocalTime time, Integer speed, Scenario scenario) {}
    public record DayResponse(Instant sandboxNow, int speed, Scenario scenario, int arrivalsSoFar, int target) {}
    public record StateResponse(boolean enabled, Instant sandboxNow, int speed, Scenario scenario, boolean warming, Instant dayStartedAt) {}
}
