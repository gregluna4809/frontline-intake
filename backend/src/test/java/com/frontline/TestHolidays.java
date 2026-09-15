package com.frontline;

import com.frontline.business.BusinessCalendar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Calendar unit tests use the migration fixture; application code always uses the database. */
final class TestHolidays {
    static BusinessCalendar calendar(Clock clock) { return new BusinessCalendar(clock, TestHolidays::dates); }

    static Set<LocalDate> dates() {
        try (var stream = TestHolidays.class.getResourceAsStream("/db/migration/V12__administrator_management.sql")) {
            String sql = new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
            var matcher = Pattern.compile("'(\\d{4}-\\d{2}-\\d{2})'").matcher(sql);
            var dates = new HashSet<LocalDate>();
            while (matcher.find()) dates.add(LocalDate.parse(matcher.group(1)));
            return dates;
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }
}
