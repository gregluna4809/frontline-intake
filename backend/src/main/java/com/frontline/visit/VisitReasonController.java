package com.frontline.visit;

import com.frontline.business.BusinessCalendar;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
public class VisitReasonController {
    private final VisitReasonRepository reasons;
    private final BusinessCalendar calendar;
    public VisitReasonController(VisitReasonRepository reasons, BusinessCalendar calendar) {
        this.reasons = reasons; this.calendar = calendar;
    }
    @GetMapping("/api/visit-reasons")
    public List<VisitReasonRepository.Reason> list(@RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reasons.active(date == null ? calendar.currentBusinessDate() : date);
    }
}
