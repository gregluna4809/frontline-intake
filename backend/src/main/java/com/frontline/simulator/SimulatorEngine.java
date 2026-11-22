package com.frontline.simulator;

import com.frontline.business.BusinessCalendar;
import com.frontline.model.Visit;
import com.frontline.model.Resource;
import com.frontline.visit.VisitService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SimulatorEngine {
    private final SimulatorRepository repository;
    private final SimulatorProperties settings;
    private final SimulatorWork work;
    private final VisitService visits;
    public SimulatorEngine(SimulatorRepository repository, SimulatorProperties settings, SimulatorWork work, VisitService visits) {
        this.repository = repository; this.settings = settings; this.work = work; this.visits = visits;
    }
    public int tick(LocalDate date, Instant now) {
        var day = repository.day(date).orElseGet(() -> {
            long seed = new Random().nextLong();
            return repository.createDay(date, seed, settings.rangeFor(date).draw(new Random(seed)));
        });
        if (day.sweptAt() != null) return 0;
        int created = 0;
        if (repository.enabled("ARRIVALS")) {
            int due = (int) ArrivalSchedule.times(day, settings).stream().filter(time -> !time.isAfter(now)).count();
            for (int i = day.arrivalsSoFar(); i < due; i++) if (work.arrive(date, i, now)) created++;
        }
        if (!now.atZone(BusinessCalendar.ZONE).toLocalTime().isBefore(LocalTime.of(16, 40))) {
            work.sweep(date, now);
            return created;
        }
        for (var task : repository.due(date, now)) {
            try { work.task(task.id(), now); }
            catch (ResponseStatusException exception) {
                if (exception.getStatusCode().value() != 409 && exception.getStatusCode().value() != 412) throw exception;
                repository.done(task.id(), now, "SKIPPED");
            }
        }
        {
            boolean seating = repository.enabled("RR_LSRS");
            Set<Resource.Type> blocked = new HashSet<>();
            for (var item : visits.list(Visit.Status.WAITING_RESOURCE, date)) {
                if (item.visit().visitType() != Visit.VisitType.WALK_IN) continue;
                work.watchWaiting(item.visit().id(), now);
                if (!seating || blocked.contains(item.requiredResourceType())) continue;
                try { if (!work.seat(item.visit().id(), now)) blocked.add(item.requiredResourceType()); }
                catch (ResponseStatusException exception) {
                    if (exception.getStatusCode().value() != 409 && exception.getStatusCode().value() != 412) throw exception;
                    blocked.add(item.requiredResourceType());
                }
            }
        }
        return created;
    }
}
