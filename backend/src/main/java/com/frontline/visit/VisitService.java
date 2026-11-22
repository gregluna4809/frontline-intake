package com.frontline.visit;

import com.frontline.customer.CustomerRepository;
import com.frontline.business.BusinessCalendar;
import com.frontline.business.BusinessDayService;
import com.frontline.business.TicketSequenceService;
import java.time.LocalDate;
import com.frontline.model.Customer;
import com.frontline.model.Resource;
import com.frontline.model.Staff;
import com.frontline.model.Visit;
import com.frontline.model.VisitEvent;
import com.frontline.resource.ResourceRepository;
import com.frontline.staff.StaffRepository;
import java.util.List;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VisitService {
    private final VisitRepository repository;
    private final CustomerRepository customers;
    private final ResourceRepository resources;
    private final StaffRepository staff;
    private final BusinessCalendar calendar;
    private final TicketSequenceService tickets;
    private final BusinessDayService days;
    private final VisitReasonRepository reasons;
    private final com.frontline.resource.ResourceSessionRepository sessions;

    public VisitService(VisitRepository repository, CustomerRepository customers, ResourceRepository resources,
                        StaffRepository staff, BusinessCalendar calendar, TicketSequenceService tickets, BusinessDayService days,
                        VisitReasonRepository reasons, com.frontline.resource.ResourceSessionRepository sessions) {
        this.repository = repository;
        this.customers = customers;
        this.resources = resources;
        this.staff = staff;
        this.calendar = calendar;
        this.tickets = tickets;
        this.days = days;
        this.reasons = reasons;
        this.sessions = sessions;
    }

    @Transactional
    public Visit checkIn(Visit.VisitType visitType, Visit.Reason reason, Visit.AppointmentCategory appointmentCategory) {
        return checkIn(visitType, reason, appointmentCategory, "KIOSK");
    }

    @Transactional
    public Visit checkIn(Visit.VisitType visitType, Visit.Reason reason,
                         Visit.AppointmentCategory appointmentCategory, String actor) {
        boolean walkIn = visitType == Visit.VisitType.WALK_IN && reason != null
                && appointmentCategory == null;
        boolean appointment = visitType == Visit.VisitType.APPOINTMENT && appointmentCategory != null
                && reason == null;
        if (!walkIn && !appointment) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WALK_IN requires only reason; APPOINTMENT requires appointmentCategory only.");
        }
        Visit.AppointmentStatus appointmentStatus = appointment ? Visit.AppointmentStatus.SCHEDULED : null;
        LocalDate date = tickets.prepareForCreation();
        reasons.requireActive(reason, date);
        Visit visit = repository.create(visitType, reason, appointmentCategory, appointmentStatus, date);
        if ("KIOSK".equals(actor)) repository.recordCheckIn(visit);
        else repository.recordCheckIn(visit, actor);
        return visit;
    }

    @Transactional(readOnly = true)
    public List<VisitQueueItem> list(Visit.Status status) {
        return list(status, null);
    }

    @Transactional(readOnly = true)
    public List<VisitQueueItem> list(Visit.Status status, LocalDate businessDate) {
        return list(status, businessDate, null);
    }

    @Transactional(readOnly = true)
    public List<VisitQueueItem> list(Visit.Status status, LocalDate businessDate, Long assignedStaffId) {
        return repository.findAll(status, businessDate == null ? calendar.currentBusinessDate() : businessDate, assignedStaffId);
    }

    @Transactional(readOnly = true)
    public VisitDetail detail(long id) {
        return describe(findVisit(id));
    }

    @Transactional(readOnly = true)
    public List<String> activeReasonCodes() {
        return reasons.active(calendar.currentBusinessDate()).stream().map(VisitReasonRepository.Reason::code).toList();
    }

    @Transactional
    public VisitDetail identify(long id, long customerId, long expectedVersion) {
        return identify(id, customerId, expectedVersion, null);
    }

    @Transactional
    public VisitDetail identify(long id, long customerId, long expectedVersion, String actor) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.CHECKED_IN);
        reasons.requireActive(previous.reason(), previous.businessDate());
        customers.findById(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found."));
        Visit updated = transition(previous, Visit.Status.IDENTIFIED, customerId, null, null, actor);
        return describe(updated);
    }

    @Transactional
    public VisitDetail route(long id, long expectedVersion) {
        return route(id, expectedVersion, null);
    }

    @Transactional
    public VisitDetail route(long id, long expectedVersion, String actor) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.IDENTIFIED);
        if (previous.visitType() == Visit.VisitType.APPOINTMENT) {
            return describe(transition(previous, Visit.Status.WAITING_STAFF, previous.customerId(), null, null, actor));
        }
        Resource resource = resources.assignLowestFree(previous.requiredResourceType(), previous.businessDate()).orElse(null);
        if (resource != null) {
            resources.setOccupant(resource.id(), previous.id());
            sessions.open(previous, resource.id());
        }
        Visit.Status next = resource == null ? Visit.Status.WAITING_RESOURCE : Visit.Status.AT_RESOURCE;
        Visit updated = transition(previous, next, previous.customerId(), resource == null ? null : resource.id(), null, actor);
        return describe(updated);
    }

    @Transactional
    public VisitDetail close(long id, long expectedVersion) {
        return close(id, expectedVersion, null);
    }

    @Transactional
    public VisitDetail close(long id, long expectedVersion, Long callerLsrId) {
        return close(id, expectedVersion, callerLsrId, null);
    }

    @Transactional
    public VisitDetail close(long id, long expectedVersion, Long callerLsrId, String actor) {
        Visit previous = requireStatus(requireVersion(id, expectedVersion, callerLsrId),
                Visit.Status.AT_RESOURCE, Visit.Status.WAITING_STAFF);
        return finish(previous, Visit.Status.CLOSED, null, actor);
    }

    @Transactional
    public RescheduleResult reschedule(long id, long expectedVersion) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.WAITING_STAFF);
        if (previous.visitType() != Visit.VisitType.APPOINTMENT || previous.customerId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rescheduling requires an identified appointment.");
        }
        LocalDate businessDate = tickets.prepareForCreation();
        VisitDetail closed = finish(previous, Visit.Status.CLOSED,
                "Rescheduled appointment; replacement links to this visit via previousVisitId.");
        Visit replacement = repository.createRescheduled(previous, businessDate);
        repository.recordRescheduled(replacement);
        return new RescheduleResult(closed, describe(replacement));
    }

    public record RescheduleResult(VisitDetail closedVisit, VisitDetail replacementVisit) {}

    @Transactional
    public VisitDetail left(long id, long expectedVersion) {
        return left(id, expectedVersion, null);
    }

    @Transactional
    public VisitDetail left(long id, long expectedVersion, Long callerLsrId) {
        return left(id, expectedVersion, callerLsrId, null);
    }

    @Transactional
    public VisitDetail left(long id, long expectedVersion, Long callerLsrId, String actor) {
        Visit previous = requireVersion(id, expectedVersion, callerLsrId);
        if (previous.status() == Visit.Status.CLOSED || previous.status() == Visit.Status.LEFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A completed visit cannot be marked left.");
        }
        return finish(previous, Visit.Status.LEFT, null, actor);
    }

    private void requireAppointmentOwner(Visit visit, Long callerLsrId) {
        if (callerLsrId != null && visit.visitType() == Visit.VisitType.APPOINTMENT
                && !callerLsrId.equals(visit.assignedStaffId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "LSRs may only close or mark left appointments assigned to themselves.");
        }
    }

    @Transactional
    public VisitDetail assign(long id, long expectedVersion) {
        return assign(id, expectedVersion, null);
    }

    @Transactional
    public VisitDetail assign(long id, long expectedVersion, String actor) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.WAITING_RESOURCE);
        Resource resource = resources.assignLowestFree(previous.requiredResourceType(), previous.businessDate())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No matching resource is free. Refresh the inventory before trying again."));
        resources.setOccupant(resource.id(), previous.id());
        sessions.open(previous, resource.id());
        return describe(transition(previous, Visit.Status.AT_RESOURCE, previous.customerId(), resource.id(), null, actor));
    }

    @Transactional
    public VisitDetail phoneOverride(long id, long expectedVersion, String note) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.AT_RESOURCE,
                Visit.Status.WAITING_RESOURCE, Visit.Status.IDENTIFIED);
        if (note == null || note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A phone override note is required.");
        }
        return moveToPhone(previous, "phone override: " + note.strip());
    }

    @Transactional
    public VisitDetail needsPhone(long id, long requestId) {
        days.resetResourcesForOpening();
        Visit previous = requireStatus(findVisit(id), Visit.Status.AT_RESOURCE);
        return moveToPhone(previous, "help request " + requestId + ": needs phone");
    }

    private VisitDetail moveToPhone(Visit previous, String note) {
        if (previous.visitType() != Visit.VisitType.WALK_IN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone handoff requires a walk-in.");
        }
        sessions.end(previous.id(), "TO_PHONE");
        if (previous.resourceId() != null) resources.release(previous.resourceId(), previous.id());
        repository.requestPhone(previous.id());
        Resource resource = resources.assignLowestFree(Resource.Type.PHONE, previous.businessDate()).orElse(null);
        if (resource != null) {
            resources.setOccupant(resource.id(), previous.id());
            sessions.open(previous, resource.id());
        }
        Visit updated = transition(previous, resource == null ? Visit.Status.WAITING_RESOURCE : Visit.Status.AT_RESOURCE,
                previous.customerId(), resource == null ? null : resource.id(), note);
        return describe(updated);
    }

    @Transactional
    public VisitDetail assignStaff(long id, long staffId, long expectedVersion) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.WAITING_STAFF);
        Staff target = staff.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found."));
        if (!target.active() || target.role() != Staff.Role.LSR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Visits can only be assigned to an active LSR.");
        }
        Visit updated = repository.assignStaff(previous, staffId).orElseThrow(this::staleVersion);
        repository.recordTransition(previous, updated);
        return describe(updated);
    }

    @Transactional
    public VisitDetail reportFtr(long id, long expectedVersion) {
        Visit previous = requireVersion(id, expectedVersion);
        if (previous.appointmentStatus() != Visit.AppointmentStatus.NOT_ATTENDED_FTR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reporting FTR requires NOT_ATTENDED_FTR.");
        }
        Visit updated = repository.reportFtr(previous).orElseThrow(this::staleVersion);
        repository.recordTransition(previous, updated,
                "Customer made contact: NOT_ATTENDED_FTR -> FTR_REPORTED.", "APPOINTMENTS");
        return describe(updated);
    }

    private VisitDetail finish(Visit previous, Visit.Status next) {
        return finish(previous, next, null);
    }

    private VisitDetail finish(Visit previous, Visit.Status next, String note) {
        return finish(previous, next, note, null);
    }

    private VisitDetail finish(Visit previous, Visit.Status next, String note, String actor) {
        sessions.end(previous.id(), next.name());
        // Resource rows are locked before the visit update, matching assignment
        // lock order. An optimistic conflict or event failure rolls back both.
        if (previous.resourceId() != null) resources.release(previous.resourceId(), previous.id());
        return describe(transition(previous, next, previous.customerId(), previous.resourceId(), note, actor));
    }

    private Visit transition(Visit previous, Visit.Status next, Long customerId, Long resourceId) {
        return transition(previous, next, customerId, resourceId, null);
    }

    private Visit transition(Visit previous, Visit.Status next, Long customerId, Long resourceId, String note) {
        return transition(previous, next, customerId, resourceId, note, null);
    }

    private Visit transition(Visit previous, Visit.Status next, Long customerId, Long resourceId, String note, String actor) {
        Visit updated = repository.transition(previous, next, customerId, resourceId)
                .orElseThrow(this::staleVersion);
        if (actor != null) repository.recordTransition(previous, updated, note, actor);
        else if (note == null) repository.recordTransition(previous, updated);
        else repository.recordTransition(previous, updated, note);
        return updated;
    }

    private Visit requireStatus(long id, long expectedVersion, Visit.Status... allowed) {
        return requireStatus(requireVersion(id, expectedVersion), allowed);
    }

    private Visit requireStatus(Visit visit, Visit.Status... allowed) {
        if (Arrays.stream(allowed).noneMatch(status -> visit.status() == status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This action requires " + Arrays.toString(allowed) + ". Reload the visit to see its current status.");
        }
        return visit;
    }

    private Visit requireVersion(long id, long expectedVersion) {
        return requireVersion(id, expectedVersion, null);
    }

    private Visit requireVersion(long id, long expectedVersion, Long callerLsrId) {
        days.resetResourcesForOpening();
        Visit visit = findVisit(id);
        requireAppointmentOwner(visit, callerLsrId);
        if (visit.version() != expectedVersion) throw staleVersion();
        return visit;
    }

    private ResponseStatusException staleVersion() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "This visit was changed by another action. Reload the visit before continuing.");
    }

    private Visit findVisit(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Visit not found."));
    }

    private VisitDetail describe(Visit visit) {
        Customer customer = visit.customerId() == null ? null : customers.findById(visit.customerId()).orElse(null);
        Resource resource = visit.resourceId() == null ? null : resources.findById(visit.resourceId()).orElse(null);
        Visit missedAppointment = customer == null ? null : repository.latestOtherAppointment(customer.id(), visit.id())
                .filter(appointment -> appointment.appointmentStatus() == Visit.AppointmentStatus.NOT_ATTENDED_FTR)
                .orElse(null);
        return new VisitDetail(visit, repository.findEvents(visit.id()), customer, resource,
                visit.requiredResourceType(), missedAppointment);
    }

    public record VisitDetail(
            Visit visit, List<VisitEvent> events, Customer customer, Resource resource, Resource.Type requiredResourceType,
            Visit missedAppointment
    ) {}
}
