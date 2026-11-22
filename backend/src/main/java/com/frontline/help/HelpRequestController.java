package com.frontline.help;

import com.frontline.model.HelpRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class HelpRequestController {
    private final HelpRequestService service;
    public HelpRequestController(HelpRequestService service) { this.service = service; }
    @PostMapping("/api/visits/{id}/help-requests")
    public ResponseEntity<HelpRequest> raise(@PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version, @AuthenticationPrincipal Jwt caller) {
        HelpRequest request = service.raise(id, expectedVersion(version), staffId(caller));
        return ResponseEntity.created(URI.create("/api/help-requests/" + request.id()))
                .eTag(Long.toString(request.version())).body(request);
    }
    @GetMapping("/api/help-requests")
    public List<HelpRequest> list(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "true") boolean open) { return service.list(businessDate, open); }
    @PostMapping("/api/help-requests/{id}/claim")
    public ResponseEntity<HelpRequest> claim(@PathVariable long id,
            @RequestHeader(value = "If-Match", required = false) String version, @AuthenticationPrincipal Jwt caller) {
        return response(service.claim(id, expectedVersion(version), staffId(caller)));
    }
    @PostMapping("/api/help-requests/{id}/close")
    public ResponseEntity<HelpRequest> close(@PathVariable long id, @Valid @RequestBody CloseRequest input,
            @RequestHeader(value = "If-Match", required = false) String version, @AuthenticationPrincipal Jwt caller) {
        return response(service.close(id, expectedVersion(version), staffId(caller), caller.getClaimAsString("role"), input.outcome()));
    }
    private ResponseEntity<HelpRequest> response(HelpRequest request) {
        return ResponseEntity.ok().eTag(Long.toString(request.version())).body(request);
    }
    private long staffId(Jwt caller) { return ((Number) caller.getClaim("staffId")).longValue(); }
    private long expectedVersion(String header) {
        if (header == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required. Send the version as a quoted number.");
        if (!header.matches("\"[0-9]+\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain one quoted, nonnegative version.");
        try { return Long.parseLong(header.substring(1, header.length() - 1)); }
        catch (NumberFormatException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version is out of range."); }
    }
    public record CloseRequest(@NotNull HelpRequest.Outcome outcome) {}
}
