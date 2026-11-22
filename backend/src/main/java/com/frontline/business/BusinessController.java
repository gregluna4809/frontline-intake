package com.frontline.business;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessController {
    private final BusinessCalendar calendar;
    private final java.time.Clock clock;
    private final com.frontline.sandbox.SandboxContext sandbox;

    public BusinessController(BusinessCalendar calendar, java.time.Clock businessClock, com.frontline.sandbox.SandboxContext sandbox) { this.calendar = calendar; this.clock = businessClock; this.sandbox = sandbox; }

    @GetMapping("/api/business-status")
    public Status status() {
        var status = calendar.status();
        return new Status(status.isOpen(),status.currentBusinessDate(),status.lastCompletedBusinessDate(),clock.instant(),sandbox.enabled(),sandbox.speed());
    }
    public record Status(boolean isOpen, java.time.LocalDate currentBusinessDate, java.time.LocalDate lastCompletedBusinessDate,
                         java.time.Instant serverNow, boolean sandbox, int speed) {}
}
