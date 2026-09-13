package com.frontline.resource;

import java.util.List;
import java.time.LocalDate;
import com.frontline.business.BusinessCalendar;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final ResourceRepository repository;
    private final BusinessCalendar calendar;

    public ResourceController(ResourceRepository repository, BusinessCalendar calendar) {
        this.repository = repository;
        this.calendar = calendar;
    }

    @GetMapping
    public List<ResourceInventoryItem> inventory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return repository.inventory(businessDate == null ? calendar.currentBusinessDate() : businessDate);
    }
}
