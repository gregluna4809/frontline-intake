package com.frontline.business;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SnapshotController {
    private final BusinessDayService days;
    public SnapshotController(BusinessDayService days) { this.days = days; }

    @GetMapping("/api/snapshot")
    public JsonNode snapshot(@RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return days.snapshot(date);
    }
}
