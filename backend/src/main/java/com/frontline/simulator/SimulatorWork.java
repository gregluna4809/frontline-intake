package com.frontline.simulator;

import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import com.frontline.help.HelpRequestService;
import com.frontline.help.HelpRequestRepository;
import com.frontline.model.HelpRequest;
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
    private final HelpRequestService helpRequests;
    private final HelpRequestRepository helpRepository;
    public SimulatorWork(SimulatorRepository repository, SimulatorProperties settings, VisitService visits,
                         HelpRequestService helpRequests, HelpRequestRepository helpRepository) {
        this.repository = repository; this.settings = settings; this.visits = visits;
        this.helpRequests = helpRequests; this.helpRepository = helpRepository;
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
        if (task.action().equals("CLAIM_HELP") && !repository.enabled("RR_LSRS")) return;
        Visit visit = visits.detail(task.visitId()).visit();
        boolean completed = false;
        switch (task.action()) {
            case "RAISE_HELP" -> {
                if (visit.status() == Visit.Status.AT_RESOURCE
                        && repository.openSession(visit.id()).equals(Optional.ofNullable(task.resourceSessionId()))
                        && !helpRepository.hasOpen(visit.id())) {
                    var clerk = repository.clerk();
                    if (clerk.isEmpty()) { repository.done(id, now, "NO_CLERK"); return; }
                    HelpRequest help = helpRequests.raise(visit.id(), visit.version(), clerk.get());
                    repository.schedule(visit.id(), "CLAIM_HELP", now.plusSeconds(settings.helpClaimDelayMinutes().draw(taskRandom(task, 11)) * 60L),
                            null, task.resourceSessionId(), null, help.id());
                    completed = true;
                }
            }
            case "CLAIM_HELP" -> {
                var help = helpRepository.find(task.helpRequestId()).orElse(null);
                if (help != null && help.closedAt() == null && help.claimedByStaffId() == null) {
                    LocalDate date = visit.businessDate();
                    var staff = repository.freeStaff(date, "RESOURCE_ROOM")
                            .or(() -> repository.freeStaff(date, "APPOINTMENTS"));
                    if (staff.isEmpty()) return;
                    helpRequests.claim(help.id(), help.version(), staff.get());
                    repository.schedule(visit.id(), "FINISH_HELP", now.plusSeconds(settings.helpWorkMinutes().draw(taskRandom(task, 23)) * 60L),
                            null, help.sessionId(), staff.get(), help.id());
                    completed = true;
                }
            }
            case "FINISH_HELP" -> {
                var help = helpRepository.find(task.helpRequestId()).orElse(null);
                if (help != null && help.closedAt() == null && Objects.equals(help.claimedByStaffId(), task.staffId())
                        && visit.status() == Visit.Status.AT_RESOURCE
                        && repository.openSession(visit.id()).equals(Optional.ofNullable(task.resourceSessionId()))) {
                    var outcome = settings.helpOutcomes().draw(visit.reason(), settings.scenario(), taskRandom(task, 37));
                    helpRequests.close(help.id(), help.version(), task.staffId(), "LSR", outcome, "SIMULATOR");
                    if (outcome == HelpRequest.Outcome.REFERRED_OUT) {
                        repository.schedule(visit.id(), "END_SESSION", now.plusSeconds(settings.referredOutLeaveMinutes().draw(taskRandom(task, 41)) * 60L),
                                null, task.resourceSessionId());
                    } else if (outcome == HelpRequest.Outcome.NEEDS_PHONE) {
                        visit = visits.detail(visit.id()).visit();
                        if (visit.status() == Visit.Status.AT_RESOURCE) {
                            repository.schedule(visit.id(), "END_SESSION", now.plusSeconds(settings.sessionMinutes().phone().draw(taskRandom(task, 43)) * 60L),
                                    null, repository.openSession(visit.id()).orElseThrow());
                        } else scheduleResourceUse(visit, now);
                    }
                    completed = true;
                }
            }
            case "CLOSE_APPOINTMENT" -> {
                if (visit.visitType() == Visit.VisitType.APPOINTMENT && visit.status() == Visit.Status.WAITING_STAFF
                        && Objects.equals(visit.assignedStaffId(), task.staffId())) {
                    visits.close(visit.id(), visit.version(), task.staffId(), "SIMULATOR");
                    completed = true;
                }
            }
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
    public boolean serveAppointment(long id, Instant now) {
        repository.lock();
        if (!repository.enabled("APPOINTMENT_LSRS")) return false;
        Visit visit = visits.detail(id).visit();
        if (visit.visitType() != Visit.VisitType.APPOINTMENT || visit.status() != Visit.Status.WAITING_STAFF
                || repository.hasAppointmentTask(id)) return true;
        Long staff = visit.assignedStaffId();
        if (staff != null) {
            if (!repository.activeLsr(staff) || !repository.free(staff)) return true;
        } else {
            staff = repository.freeStaff(visit.businessDate(), "APPOINTMENTS").orElse(null);
            if (staff == null) return false;
            visit = visits.assignStaff(id, staff, visit.version(), "SIMULATOR").visit();
        }
        Random random = new Random(repository.day(visit.businessDate()).orElseThrow().seed() ^ (visit.id() * 0x9E3779B97F4A7C15L));
        repository.schedule(id, "CLOSE_APPOINTMENT", now.plusSeconds(settings.appointmentMinutes().draw(random) * 60L), null, null, staff, null);
        return true;
    }
    private Random taskRandom(SimulatorRepository.Task task, long purpose) {
        long seed = repository.day(visits.detail(task.visitId()).visit().businessDate()).orElseThrow().seed();
        return new Random(seed ^ task.visitId() * 0x9E3779B97F4A7C15L ^ task.id() * 0xD1B54A32D192ED03L ^ purpose);
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
            long fullSeconds = seconds;
            boolean early = random.nextDouble() < settings.leaveEarlyRate();
            if (early) seconds = random.nextLong(1, seconds + 1);
            repository.schedule(visit.id(), early ? "LEAVE_EARLY" : "END_SESSION", now.plusSeconds(seconds), null, session);
            if (visit.requiredResourceType() == com.frontline.model.Resource.Type.COMPUTER
                    && random.nextDouble() < settings.helpRate().getOrDefault(visit.reason().name(), 0.0)) {
                long helpSeconds = Math.round(fullSeconds * (0.10 + random.nextDouble() * 0.50));
                repository.schedule(visit.id(), "RAISE_HELP", now.plusSeconds(helpSeconds), null, session);
            }
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
        for (long id : repository.engineAppointments(date)) {
            Visit visit = visits.detail(id).visit();
            if (repository.hasAppointmentTask(id) && visit.status() == Visit.Status.WAITING_STAFF)
                visits.close(id, visit.version(), visit.assignedStaffId(), "SIMULATOR");
            else visits.left(id, visit.version(), null, "SIMULATOR");
        }
        repository.swept(date, now);
    }
}
