package com.frontline.business;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

public class BusinessCalendar {
    public static final String TIME_ZONE = "America/New_York";
    public static final ZoneId ZONE = ZoneId.of(TIME_ZONE);
    private static final LocalTime OPENING = LocalTime.of(8, 30);
    private static final LocalTime CLOSING = LocalTime.of(16, 45);

    // 2026 and 2027 closures. Saturday holidays stay Saturday; Sunday is observed Monday.
    // Includes the requested day after Thanksgiving. Extend this list before 2028.
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-19"), LocalDate.parse("2026-02-16"),
            LocalDate.parse("2026-05-25"), LocalDate.parse("2026-06-19"), LocalDate.parse("2026-07-04"),
            LocalDate.parse("2026-09-07"), LocalDate.parse("2026-10-12"), LocalDate.parse("2026-11-11"),
            LocalDate.parse("2026-11-26"), LocalDate.parse("2026-11-27"), LocalDate.parse("2026-12-25"),
            LocalDate.parse("2027-01-01"), LocalDate.parse("2027-01-18"), LocalDate.parse("2027-02-15"),
            LocalDate.parse("2027-05-31"), LocalDate.parse("2027-06-19"), LocalDate.parse("2027-07-04"),
            LocalDate.parse("2027-07-05"), LocalDate.parse("2027-09-06"), LocalDate.parse("2027-10-11"),
            LocalDate.parse("2027-11-11"), LocalDate.parse("2027-11-25"), LocalDate.parse("2027-11-26"),
            LocalDate.parse("2027-12-25"));

    private final Clock clock;

    public BusinessCalendar(Clock clock) {
        this.clock = clock;
    }

    public boolean isBusinessDay(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !HOLIDAYS.contains(date);
    }

    public boolean isOpenNow() { return status().isOpen(); }
    public LocalDate currentBusinessDate() { return status().currentBusinessDate(); }
    public LocalDate lastCompletedBusinessDate() { return status().lastCompletedBusinessDate(); }
    public LocalDate calendarDate() { return clock.instant().atZone(ZONE).toLocalDate(); }

    public BusinessStatus status() {
        var now = clock.instant().atZone(ZONE);
        LocalDate date = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        boolean businessDay = isBusinessDay(date);
        boolean open = businessDay && !time.isBefore(OPENING) && time.isBefore(CLOSING);
        LocalDate completed = businessDay && !time.isBefore(CLOSING) ? date : previousBusinessDay(date);
        return new BusinessStatus(open, open ? date : completed, completed);
    }

    private LocalDate previousBusinessDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        while (!isBusinessDay(previous)) previous = previous.minusDays(1);
        return previous;
    }

    public record BusinessStatus(boolean isOpen, LocalDate currentBusinessDate, LocalDate lastCompletedBusinessDate) {}
}
