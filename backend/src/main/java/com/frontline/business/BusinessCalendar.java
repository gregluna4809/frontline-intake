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

    private final Clock clock;
    private final java.util.function.Supplier<Set<LocalDate>> holidays;

    public BusinessCalendar(Clock clock, java.util.function.Supplier<Set<LocalDate>> holidays) {
        this.clock = clock;
        this.holidays = holidays;
    }

    public boolean isBusinessDay(LocalDate date) {
        return isBusinessDay(date, holidays.get());
    }

    private boolean isBusinessDay(LocalDate date, Set<LocalDate> dates) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY
                && !dates.contains(date);
    }

    public boolean isOpenNow() { return status().isOpen(); }
    public LocalDate currentBusinessDate() { return status().currentBusinessDate(); }
    public LocalDate lastCompletedBusinessDate() { return status().lastCompletedBusinessDate(); }
    public LocalDate calendarDate() { return clock.instant().atZone(ZONE).toLocalDate(); }

    public BusinessStatus status() {
        var now = clock.instant().atZone(ZONE);
        LocalDate date = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        Set<LocalDate> dates = holidays.get();
        boolean businessDay = isBusinessDay(date, dates);
        boolean open = businessDay && !time.isBefore(OPENING) && time.isBefore(CLOSING);
        LocalDate completed = businessDay && !time.isBefore(CLOSING) ? date : previousBusinessDay(date, dates);
        return new BusinessStatus(open, open ? date : completed, completed);
    }

    private LocalDate previousBusinessDay(LocalDate date, Set<LocalDate> dates) {
        LocalDate previous = date.minusDays(1);
        while (!isBusinessDay(previous, dates)) previous = previous.minusDays(1);
        return previous;
    }

    public record BusinessStatus(boolean isOpen, LocalDate currentBusinessDate, LocalDate lastCompletedBusinessDate) {}
}
