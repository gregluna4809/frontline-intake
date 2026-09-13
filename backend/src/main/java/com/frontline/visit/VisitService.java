package com.frontline.visit;

import com.frontline.customer.CustomerRepository;
import com.frontline.business.BusinessCalendar;
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

    public VisitService(VisitRepository repository, CustomerRepository customers, ResourceRepository resources,
                        StaffRepository staff, BusinessCalendar calendar, TicketSequenceService tickets) {
        this.repository = repository;
        this.customers = customers;
        this.resources = resources;
        this.staff = staff;
        this.calendar = calendar;
        this.tickets = tickets;
    }

    @Transactional
    public Visit checkIn(Visit.VisitType visitType, Visit.Reason reason, Visit.AppointmentCategory appointmentCategory) {
        return checkIn(visitType, reason, appointmentCategory, null, "KIOSK");
    }

    @Transactional
    public Visit checkIn(Visit.VisitType visitType, Visit.Reason reason,
                         Visit.AppointmentCategory appointmentCategory, Visit.AppointmentType appointmentType) {
        return checkIn(visitType, reason, appointmentCategory, appointmentType, "KIOSK");
    }

    @Transactional
    public Visit checkIn(Visit.VisitType visitType, Visit.Reason reason,
                         Visit.AppointmentCategory appointmentCategory, Visit.AppointmentType appointmentType,
                         String actor) {
        boolean walkIn = visitType == Visit.VisitType.WALK_IN && reason != null
                && appointmentCategory == null && appointmentType == null;
        boolean appointment = visitType == Visit.VisitType.APPOINTMENT && appointmentCategory != null
                && appointmentType != null && reason == null;
        if (!walkIn && !appointment) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WALK_IN requires only reason; APPOINTMENT requires appointmentCategory and appointmentType only.");
        }
        Visit.AppointmentStatus appointmentStatus = appointment ? Visit.AppointmentStatus.SCHEDULED : null;
        Visit visit = repository.create(visitType, reason, appointmentCategory, appointmentType, appointmentStatus, tickets.prepareForCreation());
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
        return repository.findAll(status, businessDate == null ? calendar.currentBusinessDate() : businessDate);
    }

    @Transactional(readOnly = true)
    public VisitDetail detail(long id) {
        return describe(findVisit(id));
    }

    @Transactional
    public VisitDetail identify(long id, long customerId, long expectedVersion) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.CHECKED_IN);
        customers.findById(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found."));
        Visit updated = transition(previous, Visit.Status.IDENTIFIED, customerId, null);
        return describe(updated);
    }

    @Transactional
    public VisitDetail route(long id, long expectedVersion) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.IDENTIFIED);
        if (previous.visitType() == Visit.VisitType.APPOINTMENT) {
            return describe(transition(previous, Visit.Status.WAITING_STAFF, previous.customerId(), null));
        }
        Resource resource = resources.assignLowestFree(previous.requiredResourceType()).orElse(null);
        Visit.Status next = resource == null ? Visit.Status.WAITING_RESOURCE : Visit.Status.AT_RESOURCE;
        Visit updated = transition(previous, next, previous.customerId(), resource == null ? null : resource.id());
        return describe(updated);
    }

    @Transactional
    public VisitDetail close(long id, long expectedVersion) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.AT_RESOURCE, Visit.Status.WAITING_STAFF);
        return finish(previous, Visit.Status.CLOSED);
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
        Visit previous = requireVersion(id, expectedVersion);
        if (previous.status() == Visit.Status.CLOSED || previous.status() == Visit.Status.LEFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A completed visit cannot be marked left.");
        }
        return finish(previous, Visit.Status.LEFT);
    }

    @Transactional
    public VisitDetail assign(long id, long expectedVersion) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.WAITING_RESOURCE);
        Resource resource = resources.assignLowestFree(previous.requiredResourceType())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No matching resource is free. Refresh the inventory before trying again."));
        return describe(transition(previous, Visit.Status.AT_RESOURCE, previous.customerId(), resource.id()));
    }

    @Transactional
    public VisitDetail assignStaff(long id, long staffId, long expectedVersion) {
        Visit previous = requireStatus(id, expectedVersion, Visit.Status.WAITING_STAFF);
        Staff target = staff.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found."));
        if (target.role() != Staff.Role.LSR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Visits can only be assigned to an LSR.");
        }
        Visit updated = repository.assignStaff(previous, staffId).orElseThrow(this::staleVersion);
        repository.recordTransition(previous, updated);
        return describe(updated);
    }

    private VisitDetail finish(Visit previous, Visit.Status next) {
        return finish(previous, next, null);
    }

    private VisitDetail finish(Visit previous, Visit.Status next, String note) {
        // Resource rows are locked before the visit update, matching assignment
        // lock order. An optimistic conflict or event failure rolls back both.
        if (previous.resourceId() != null) resources.release(previous.resourceId());
        return describe(transition(previous, next, previous.customerId(), previous.resourceId(), note));
    }

    private Visit transition(Visit previous, Visit.Status next, Long customerId, Long resourceId) {
        return transition(previous, next, customerId, resourceId, null);
    }

    private Visit transition(Visit previous, Visit.Status next, Long customerId, Long resourceId, String note) {
        Visit updated = repository.transition(previous, next, customerId, resourceId)
                .orElseThrow(this::staleVersion);
        if (note == null) repository.recordTransition(previous, updated);
        else repository.recordTransition(previous, updated, note);
        return updated;
    }

    private Visit requireStatus(long id, long expectedVersion, Visit.Status... allowed) {
        Visit visit = requireVersion(id, expectedVersion);
        if (Arrays.stream(allowed).noneMatch(status -> visit.status() == status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This action requires " + Arrays.toString(allowed) + ". Reload the visit to see its current status.");
        }
        return visit;
    }

    private Visit requireVersion(long id, long expectedVersion) {
        Visit visit = findVisit(id);
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
        return new VisitDetail(visit, repository.findEvents(visit.id()), customer, resource, visit.requiredResourceType());
    }

    public record VisitDetail(
            Visit visit, List<VisitEvent> events, Customer customer, Resource resource, Resource.Type requiredResourceType
    ) {}
}
