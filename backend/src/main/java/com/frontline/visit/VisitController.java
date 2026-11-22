package com.frontline.visit;

import com.frontline.model.Visit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public List<VisitQueueItem> list(@RequestParam(required = false) Visit.Status status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @AuthenticationPrincipal Jwt caller) {
        Long assignedStaffId = status == Visit.Status.WAITING_STAFF ? callerLsrId(caller) : null;
        return service.list(status, businessDate, assignedStaffId);
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

    @PostMapping("/{id}/report-ftr")
    public ResponseEntity<VisitService.VisitDetail> reportFtr(
            @PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(service.reportFtr(id, expectedVersion(version)));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<VisitService.VisitDetail> close(
            @PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version,
            @AuthenticationPrincipal Jwt caller) {
        return response(service.close(id, expectedVersion(version), callerLsrId(caller)));
    }

    @PostMapping("/{id}/reschedule")
    public ResponseEntity<VisitService.RescheduleResult> reschedule(
            @PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version) {
        var result = service.reschedule(id, expectedVersion(version));
        return ResponseEntity.created(URI.create("/api/visits/" + result.replacementVisit().visit().id()))
                .body(result);
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

    @PostMapping("/{id}/assign")
    public ResponseEntity<VisitService.VisitDetail> assign(
            @PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(service.assign(id, expectedVersion(version)));
    }

    @PostMapping("/{id}/left")
    public ResponseEntity<VisitService.VisitDetail> left(
            @PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version,
            @AuthenticationPrincipal Jwt caller) {
        return response(service.left(id, expectedVersion(version), callerLsrId(caller)));
    }

    private Long callerLsrId(Jwt caller) {
        if (!"LSR".equals(caller.getClaimAsString("role"))) return null;
        Number staffId = caller.getClaim("staffId");
        if (staffId == null || staffId.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An LSR staff identity is required.");
        }
        return staffId.longValue();
    }

    public record CheckInRequest(
            @NotNull Visit.VisitType visitType, Visit.Reason reason, Visit.AppointmentCategory appointmentCategory
    ) {}
    @PostMapping("/{id}/assign-staff")
    public ResponseEntity<VisitService.VisitDetail> assignStaff(
            @PathVariable long id,
            @Valid @RequestBody AssignStaffRequest request,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(service.assignStaff(id, request.staffId(), expectedVersion(version)));
    }

    public record AssignStaffRequest(@NotNull @Positive Long staffId) {}
    @PostMapping("/{id}/phone-override")
    public ResponseEntity<VisitService.VisitDetail> phoneOverride(@PathVariable long id,
            @Valid @RequestBody PhoneOverrideRequest request,
            @RequestHeader(value = "If-Match", required = false) String version) {
        return response(service.phoneOverride(id, expectedVersion(version), request.note()));
    }
    public record PhoneOverrideRequest(@jakarta.validation.constraints.NotBlank String note) {}
    public record IdentifyRequest(@NotNull @Positive Long customerId) {}
}
