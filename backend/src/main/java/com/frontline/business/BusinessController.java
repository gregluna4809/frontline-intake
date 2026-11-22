package com.frontline.business;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessController {
    private final BusinessCalendar calendar;
    private final java.time.Clock clock;
    private final com.frontline.sandbox.SandboxContext sandbox;
    @org.springframework.beans.factory.annotation.Value("${frontline.sandbox.url:}") private String sandboxUrl;
    @org.springframework.beans.factory.annotation.Value("${frontline.live.url:}") private String liveUrl;

    public BusinessController(BusinessCalendar calendar, java.time.Clock businessClock, com.frontline.sandbox.SandboxContext sandbox) { this.calendar = calendar; this.clock = businessClock; this.sandbox = sandbox; }

    @GetMapping("/api/business-status")
    public Status status() {
        var status = calendar.status();
        return new Status(status.isOpen(),status.currentBusinessDate(),status.lastCompletedBusinessDate(),clock.instant(),sandbox.enabled(),sandbox.speed(),emptyToNull(sandboxUrl),emptyToNull(liveUrl));
    }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    public record Status(boolean isOpen, java.time.LocalDate currentBusinessDate, java.time.LocalDate lastCompletedBusinessDate,
                         java.time.Instant serverNow, boolean sandbox, int speed, String sandboxUrl, String liveUrl) {}
}
