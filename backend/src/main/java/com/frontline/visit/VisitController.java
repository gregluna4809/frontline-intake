package com.frontline.visit;

import com.frontline.model.Visit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.HttpStatus;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/visits")
public class VisitController {
    private final VisitService service;

    public VisitController(VisitService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Visit> create(@Valid @RequestBody CheckInRequest request) {
        Visit visit = service.checkIn(request.visitType(), request.reason(), request.appointmentCategory());
        return ResponseEntity.created(URI.create("/api/visits/" + visit.id()))
                .eTag(Long.toString(visit.version())).body(visit);
    }

    @GetMapping
    public List<VisitQueueItem> list(@RequestParam(required = false) Visit.Status status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VisitService.VisitDetail> detail(@PathVariable long id) {
        return response(service.detail(id));
    }

    @PostMapping("/{id}/identify")
    public ResponseEntity<VisitService.VisitDetail> identify(
            @PathVariable long id,
            @Valid @RequestBody IdentifyRequest request,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(service.identify(id, request.customerId(), expectedVersion(version)));
    }

    @PostMapping("/{id}/route")
    public ResponseEntity<VisitService.VisitDetail> route(
            @PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(service.route(id, expectedVersion(version)));
    }

    private ResponseEntity<VisitService.VisitDetail> response(VisitService.VisitDetail detail) {
        return ResponseEntity.ok().eTag(Long.toString(detail.visit().version())).body(detail);
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<VisitService.VisitDetail> close(
            @PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(service.close(id, expectedVersion(version)));
    }

    private long expectedVersion(String header) {
        if (header == null) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. Send the visit version as a quoted number.");
        }
        if (!header.matches("\"[0-9]+\"")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "If-Match must contain one quoted, nonnegative visit version.");
        }
        try {
            return Long.parseLong(header.substring(1, header.length() - 1));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Visit version is out of range.");
        }
    }

    public record CheckInRequest(
            @NotNull Visit.VisitType visitType, Visit.Reason reason, Visit.AppointmentCategory appointmentCategory
    ) {}
    public record IdentifyRequest(@NotNull @Positive Long customerId) {}
}
