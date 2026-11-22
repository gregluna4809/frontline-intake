package com.frontline.help;

import com.frontline.business.BusinessCalendar;
import com.frontline.business.BusinessDayService;
import com.frontline.model.HelpRequest;
import com.frontline.model.Visit;
import com.frontline.visit.VisitService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HelpRequestService {
    private final HelpRequestRepository repository;
    private final VisitService visits;
    private final BusinessDayService days;
    private final BusinessCalendar calendar;
    public HelpRequestService(HelpRequestRepository repository, VisitService visits, BusinessDayService days, BusinessCalendar calendar) {
        this.repository = repository; this.visits = visits; this.days = days; this.calendar = calendar;
    }
    @Transactional
    public HelpRequest raise(long visitId, long version, long staffId) {
        days.resetResourcesForOpening();
        Visit visit = visits.detail(visitId).visit();
        if (visit.version() != version) throw stale();
        if (visit.status() != Visit.Status.AT_RESOURCE) throw conflict("Help requires a visit at a computer.");
        long session = repository.computerSession(visitId).orElseThrow(() -> conflict("Help requires an open computer session."));
        if (repository.hasOpen(visitId)) throw conflict("This visit already has an open help request.");
        return find(repository.raise(visitId, session, visit.businessDate(), staffId));
    }
    @Transactional(readOnly = true)
    public List<HelpRequest> list(LocalDate date, boolean open) {
        return repository.list(date == null ? calendar.currentBusinessDate() : date, open);
    }
    @Transactional
    public HelpRequest claim(long id, long version, long staffId) {
        HelpRequest request = requireVersion(id, version);
        if (request.closedAt() != null || request.claimedByStaffId() != null) throw conflict("Request must be open and unclaimed.");
        repository.claim(id, staffId);
        return find(id);
    }
    @Transactional
    public HelpRequest close(long id, long version, long staffId, String role, HelpRequest.Outcome outcome) {
        return close(id, version, staffId, role, outcome, null);
    }

    @Transactional
    public HelpRequest close(long id, long version, long staffId, String role, HelpRequest.Outcome outcome, String actor) {
        HelpRequest request = requireVersion(id, version);
        if (outcome == null || outcome == HelpRequest.Outcome.VISIT_ENDED || outcome == HelpRequest.Outcome.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose RESOLVED_ONLINE, NEEDS_PHONE or REFERRED_OUT.");
        }
        if (request.closedAt() != null || request.claimedByStaffId() == null) throw conflict("Request must be open and claimed.");
        if (role.equals("LSR") && request.claimedByStaffId() != staffId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "LSRs may only close requests they claimed.");
        }
        if (outcome == HelpRequest.Outcome.NEEDS_PHONE && repository.computerSession(request.visitId())
                .filter(session -> session == request.sessionId()).isEmpty()) throw conflict("The computer session has ended.");
        repository.close(id, staffId, outcome);
        if (outcome == HelpRequest.Outcome.NEEDS_PHONE) visits.needsPhone(request.visitId(), id, actor);
        return find(id);
    }
    private HelpRequest requireVersion(long id, long version) {
        days.resetResourcesForOpening();
        HelpRequest request = find(id);
        if (request.version() != version) throw stale();
        return request;
    }
    private HelpRequest find(long id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Help request not found."));
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException stale() { return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Help request changed. Reload before continuing."); }
}
