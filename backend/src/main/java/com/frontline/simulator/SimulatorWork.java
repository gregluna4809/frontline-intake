package com.frontline.simulator;

import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SimulatorWork {
    private final SimulatorRepository repository;
    private final SimulatorProperties settings;
    private final VisitService visits;
    public SimulatorWork(SimulatorRepository repository, SimulatorProperties settings, VisitService visits) {
        this.repository = repository; this.settings = settings; this.visits = visits;
    }
    public boolean arrive(LocalDate date, int ordinal, Instant now) {
        repository.lock();
        var day = repository.day(date).orElseThrow();
        if (day.sweptAt() != null || day.arrivalsSoFar() != ordinal || !repository.enabled("ARRIVALS")) return false;
        Random random = new Random(day.seed() + ordinal * 104729L + 17);
        var active = visits.activeReasonCodes();
        Map<String,Integer> weights = new TreeMap<>();
        settings.weights().forEach((reason, weight) -> { if (reason.equals("APPOINTMENT") || active.contains(reason)) weights.put(reason, weight); });
        if (weights.values().stream().mapToInt(Integer::intValue).sum() == 0) return false;
        String choice = ArrivalSchedule.draw(weights, random);
        int language = random.nextInt(100);
        long customer = repository.customer(language < 85 ? "EN" : language < 97 ? "ES" : "OTHER", random.nextInt(100) < 5);
        Visit visit = choice.equals("APPOINTMENT")
                ? visits.checkIn(Visit.VisitType.APPOINTMENT, null,
                    Visit.AppointmentCategory.valueOf(ArrivalSchedule.draw(settings.appointmentWeights(), random)), "SIMULATOR")
                : visits.checkIn(Visit.VisitType.WALK_IN, Visit.Reason.valueOf(choice), null, "SIMULATOR");
        repository.schedule(visit.id(), "IDENTIFY_AND_ROUTE", now.plusSeconds(settings.clerkDelayMinutes().draw(random) * 60L), customer, null);
        repository.countArrival(date);
        return true;
    }
    public void task(long id, Instant now) {
        repository.lock();
        var task = repository.task(id).orElseThrow();
        if (task.doneAt() != null) return;
        if (task.action().equals("IDENTIFY_AND_ROUTE") && !repository.enabled("CLERKS")) return;
        Visit visit = visits.detail(task.visitId()).visit();
        boolean completed = false;
        switch (task.action()) {
            case "IDENTIFY_AND_ROUTE" -> {
                if (visit.status() == Visit.Status.CHECKED_IN) {
                    visit = visits.identify(visit.id(), task.customerId(), visit.version(), "SIMULATOR").visit();
                    visit = visits.route(visit.id(), visit.version(), "SIMULATOR").visit();
                    scheduleResourceUse(visit, now);
                    completed = true;
                }
            }
            case "END_SESSION", "LEAVE_EARLY" -> {
                if (visit.status() == Visit.Status.AT_RESOURCE && repository.openSession(visit.id()).equals(Optional.ofNullable(task.resourceSessionId()))) {
                    if (task.action().equals("END_SESSION")) visits.close(visit.id(), visit.version(), null, "SIMULATOR");
                    else visits.left(visit.id(), visit.version(), null, "SIMULATOR");
                    completed = true;
                }
            }
            case "LEAVE_WAITING" -> {
                if (visit.status() == Visit.Status.WAITING_RESOURCE) {
                    visits.left(visit.id(), visit.version(), null, "SIMULATOR");
                    completed = true;
                }
            }
            default -> throw new IllegalStateException("Unknown simulator task: " + task.action());
        }
        repository.done(id, now, completed ? "DONE" : "SKIPPED");
    }
    public boolean seat(long id, Instant now) {
        repository.lock();
        if (!repository.enabled("RR_LSRS")) return false;
        Visit visit = visits.detail(id).visit();
        if (visit.status() != Visit.Status.WAITING_RESOURCE || visit.visitType() != Visit.VisitType.WALK_IN) return false;
        visit = visits.assign(visit.id(), visit.version(), "SIMULATOR").visit();
        repository.skipWaiting(id, now);
        scheduleResourceUse(visit, now);
        return true;
    }
    public void watchWaiting(long id, Instant now) {
        repository.lock();
        Visit visit = visits.detail(id).visit();
        if (visit.status() == Visit.Status.WAITING_RESOURCE && visit.visitType() == Visit.VisitType.WALK_IN
                && !repository.hasWaitingTask(id)) scheduleResourceUse(visit, now);
    }
    private void scheduleResourceUse(Visit visit, Instant now) {
        Random random = new Random(repository.day(visit.businessDate()).orElseThrow().seed() + visit.id() * 130363L);
        if (visit.status() == Visit.Status.WAITING_RESOURCE && visit.visitType() == Visit.VisitType.WALK_IN) {
            repository.schedule(visit.id(), "LEAVE_WAITING", now.plusSeconds(random.nextInt(45, 91) * 60L), null, null);
        } else if (visit.status() == Visit.Status.AT_RESOURCE) {
            long session = repository.openSession(visit.id()).orElseThrow();
            if (repository.hasSessionTask(session)) return;
            var range = switch (visit.requiredResourceType()) {
                case PHONE -> settings.sessionMinutes().phone();
                case COMPUTER -> visit.reason() == Visit.Reason.UI_ACCOUNT ? settings.sessionMinutes().uiAccount() : settings.sessionMinutes().computer();
                case STATION -> settings.sessionMinutes().station();
            };
            long seconds = range.draw(random) * 60L;
            boolean early = random.nextDouble() < settings.leaveEarlyRate();
            if (early) seconds = random.nextLong(1, seconds + 1);
            repository.schedule(visit.id(), early ? "LEAVE_EARLY" : "END_SESSION", now.plusSeconds(seconds), null, session);
        }
    }
    public void sweep(LocalDate date, Instant now) {
        repository.lock();
        if (repository.day(date).orElseThrow().sweptAt() != null) return;
        for (long id : repository.engineWalkIns(date)) {
            Visit visit = visits.detail(id).visit();
            if (visit.status() == Visit.Status.AT_RESOURCE) visits.close(id, visit.version(), null, "SIMULATOR");
            else visits.left(id, visit.version(), null, "SIMULATOR");
        }
        repository.swept(date, now);
    }
}
