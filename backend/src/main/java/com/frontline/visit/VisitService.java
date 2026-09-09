package com.frontline.visit;

import com.frontline.customer.CustomerRepository;
import com.frontline.model.Customer;
import com.frontline.model.Resource;
import com.frontline.model.Visit;
import com.frontline.model.VisitEvent;
import com.frontline.resource.ResourceRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VisitService {
    private final VisitRepository repository;
    private final CustomerRepository customers;
    private final ResourceRepository resources;

    public VisitService(VisitRepository repository, CustomerRepository customers, ResourceRepository resources) {
        this.repository = repository;
        this.customers = customers;
        this.resources = resources;
    }

    @Transactional
    public Visit checkIn(Visit.Reason reason) {
        Visit visit = repository.create(reason);
        repository.recordCheckIn(visit);
        return visit;
    }

    @Transactional(readOnly = true)
    public List<Visit> list(Visit.Status status) {
        return repository.findAll(status);
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
        Resource resource = resources.assignLowestFree(requiredResourceType(previous)).orElse(null);
        Visit.Status next = resource == null ? Visit.Status.WAITING_RESOURCE : Visit.Status.AT_RESOURCE;
        Visit updated = transition(previous, next, previous.customerId(), resource == null ? null : resource.id());
        return describe(updated);
    }

    private Visit transition(Visit previous, Visit.Status next, Long customerId, Long resourceId) {
        Visit updated = repository.transition(previous, next, customerId, resourceId)
                .orElseThrow(this::staleVersion);
        repository.recordTransition(previous, updated);
        return updated;
    }

    private Visit requireStatus(long id, long expectedVersion, Visit.Status status) {
        Visit visit = findVisit(id);
        if (visit.version() != expectedVersion) throw staleVersion();
        if (visit.status() != status) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This action requires " + status + ". Reload the visit to see its current status.");
        }
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
        return new VisitDetail(visit, repository.findEvents(visit.id()), customer, resource, requiredResourceType(visit));
    }

    private Resource.Type requiredResourceType(Visit visit) {
        if (visit.visitType() == Visit.VisitType.APPOINTMENT) return null;
        return switch (visit.reason()) {
            case PHONE_ACCESS -> Resource.Type.PHONE;
            case COMPUTER_ACCESS, JOB_SEARCH -> Resource.Type.COMPUTER;
            case DOCUMENT_SERVICES, BENEFITS_ASSISTANCE, OTHER -> Resource.Type.STATION;
        };
    }

    public record VisitDetail(
            Visit visit, List<VisitEvent> events, Customer customer, Resource resource, Resource.Type requiredResourceType
    ) {}
}
