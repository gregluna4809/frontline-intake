package com.frontline;

import com.frontline.business.BusinessCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class BusinessCalendarTest {
    private BusinessCalendar at(String easternTime) {
        Instant instant = ZonedDateTime.parse(easternTime + "[America/New_York]").toInstant();
        return TestHolidays.calendar(Clock.fixed(instant, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-08T08:29:00-04:00,false,2026-09-04,2026-09-04",
            "2026-09-08T08:30:00-04:00,true,2026-09-08,2026-09-04",
            "2026-09-08T16:44:00-04:00,true,2026-09-08,2026-09-04",
            "2026-09-08T16:45:00-04:00,false,2026-09-08,2026-09-08",
            "2026-09-12T12:00:00-04:00,false,2026-09-11,2026-09-11",
            "2026-09-13T12:00:00-04:00,false,2026-09-11,2026-09-11",
            "2026-09-14T08:29:00-04:00,false,2026-09-11,2026-09-11",
            "2027-01-01T12:00:00-05:00,false,2026-12-31,2026-12-31",
            "2026-11-30T08:29:00-05:00,false,2026-11-25,2026-11-25"
    })
    void resolvesHoursWeekendsAndLastCompletedDay(String time, boolean open, LocalDate current, LocalDate completed) {
        BusinessCalendar calendar = at(time);
        assertThat(calendar.isOpenNow()).isEqualTo(open);
        assertThat(calendar.currentBusinessDate()).isEqualTo(current);
        assertThat(calendar.lastCompletedBusinessDate()).isEqualTo(completed);
        assertThat(calendar.status()).isEqualTo(new BusinessCalendar.BusinessStatus(open, current, completed));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-01-01", "2026-01-19", "2026-02-16", "2026-05-25", "2026-06-19", "2026-07-04",
            "2026-09-07", "2026-10-12", "2026-11-11", "2026-11-26", "2026-11-27", "2026-12-25",
            "2027-01-01", "2027-01-18", "2027-02-15", "2027-05-31", "2027-06-19", "2027-07-04",
            "2027-07-05", "2027-09-06", "2027-10-11", "2027-11-11", "2027-11-25", "2027-11-26", "2027-12-25"
    })
    void listedHolidaysAreClosed(String date) {
        var noon = LocalDate.parse(date).atTime(12, 0).atZone(BusinessCalendar.ZONE);
        BusinessCalendar calendar = TestHolidays.calendar(Clock.fixed(noon.toInstant(), ZoneOffset.UTC));
        assertThat(calendar.isBusinessDay(noon.toLocalDate())).isFalse();
        assertThat(calendar.isOpenNow()).isFalse();
        assertThat(calendar.currentBusinessDate()).isBefore(noon.toLocalDate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-07-03", "2027-06-18", "2027-12-24"})
    void saturdayHolidaysDoNotCloseFriday(String date) {
        Instant noon = LocalDate.parse(date).atTime(12, 0).atZone(BusinessCalendar.ZONE).toInstant();
        assertThat(TestHolidays.calendar(Clock.fixed(noon, ZoneOffset.UTC)).isOpenNow()).isTrue();
    }

    @Test
    void openingUsesEasternTimeAcrossDaylightSavingChanges() {
        for (String instant : new String[] {"2026-03-06T13:30:00Z", "2026-03-09T12:30:00Z",
                "2026-10-30T12:30:00Z", "2026-11-02T13:30:00Z"}) {
            var clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
            assertThat(TestHolidays.calendar(clock).isOpenNow()).isTrue();
            assertThat(TestHolidays.calendar(Clock.offset(clock, java.time.Duration.ofMinutes(-1))).isOpenNow()).isFalse();
        }
    }
}
