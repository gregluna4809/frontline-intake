package com.frontline.coverage;

import com.frontline.model.CallOut;
import com.frontline.business.BusinessCalendar;
import com.frontline.business.BusinessDayService;
import com.frontline.model.Staff;
import com.frontline.model.StaffDuty;
import com.frontline.staff.StaffRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CoverageService {
    private final CoverageRepository repository;
    private final StaffRepository staff;
    private final BusinessCalendar calendar;
    private final BusinessDayService days;

    public CoverageService(CoverageRepository repository, StaffRepository staff, BusinessCalendar calendar, BusinessDayService days) {
        this.repository = repository;
        this.staff = staff;
        this.calendar = calendar;
        this.days = days;
    }

    @Transactional(readOnly = true)
    public List<StaffDuty> rotation(LocalDate date) {
        return repository.rotation(requireDate(date));
    }

    @Transactional(readOnly = true)
    public List<CallOut> callOuts(LocalDate date) {
        return repository.callOuts(requireDate(date));
    }

    @Transactional
    public CallOut callOut(long staffId, LocalDate date, String reason) {
        if (requireDate(date).isBefore(calendar.calendarDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call-out date cannot be in the past.");
        }
        Staff target = staff.findById(staffId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff not found."));
        if (target.role() != Staff.Role.LSR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Call-outs require an LSR.");
        }
        days.freezeCompletedDays();
        return repository.upsert(staffId, date, reason == null || reason.isBlank() ? null : reason.strip());
    }

    private LocalDate requireDate(LocalDate date) {
        if (date == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date is required in YYYY-MM-DD format.");
        }
        return date;
    }
}
