package com.frontline.report;

import com.frontline.business.HolidayRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {
    private final ReportRepository repository;
    private final HolidayRepository holidays;

    public ReportService(ReportRepository repository, HolidayRepository holidays) {
        this.repository = repository;
        this.holidays = holidays;
    }

    // All aggregates and the holiday calendar see the same committed database state.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ReportSummary> summary(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw invalid("Both from and to are required in YYYY-MM-DD format.");
        if (from.isAfter(to)) throw invalid("from must be on or before to.");
        if (ChronoUnit.DAYS.between(from, to) >= 90) throw invalid("Report range must not exceed 90 calendar days, including both endpoints.");

        var activity = repository.activity(from, to).stream()
                .collect(Collectors.toMap(ReportRepository.DayActivity::date, Function.identity()));
        var closures = repository.closures(from, to).stream()
                .collect(Collectors.groupingBy(ReportRepository.Closures::date));
        var callOuts = repository.callOutCounts(from, to);
        var holidayDates = holidays.dates();
        List<ReportSummary> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY
                    || holidayDates.contains(date)) continue;
            var day = activity.getOrDefault(date, new ReportRepository.DayActivity(
                    date, ReportRepository.emptyVolume(), 0, 0, null, 0));
            var closed = closures.getOrDefault(date, List.of());
            long closedCount = closed.stream().mapToLong(ReportRepository.Closures::count).sum();
            Double duration = closedCount == 0 ? null
                    : closed.stream().mapToDouble(ReportRepository.Closures::durationMinutes).sum() / closedCount;
            var workload = closed.stream().filter(group -> group.staffId() != null)
                    .map(group -> new ReportSummary.StaffWorkload(group.staffId(), group.displayName(), group.count())).toList();
            result.add(new ReportSummary(date, day.volume(),
                    new ReportSummary.Ftr(day.scheduled(), day.ftr(), day.scheduled() == 0 ? 0.0 : (double) day.ftr() / day.scheduled()),
                    day.resourceWait(), duration, workload, callOuts.getOrDefault(date, 0L), day.reschedules()));
        }
        return result;
    }

    private ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
