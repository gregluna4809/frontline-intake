package com.frontline.business;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessController {
    private final BusinessCalendar calendar;

    public BusinessController(BusinessCalendar calendar) { this.calendar = calendar; }

    @GetMapping("/api/business-status")
    public BusinessCalendar.BusinessStatus status() { return calendar.status(); }
}
