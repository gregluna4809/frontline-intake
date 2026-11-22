package com.frontline.business;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketSequenceService {
    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;
    private final BusinessDayService days;

    public TicketSequenceService(JdbcClient jdbc, BusinessCalendar calendar, BusinessDayService days) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.days = days;
    }

    @Transactional
    public LocalDate prepareForCreation() {
        days.resetResourcesForOpening();
        lockSequences();
        LocalDate date = calendar.currentBusinessDate();
        if (calendar.alwaysOpen()) return date;
        // Catch up after downtime and cover arrivals racing the opening cron.
        // With no earlier visits, leave the initial sequence configuration intact.
        if (!hasVisits(date) && jdbc.sql("SELECT EXISTS (SELECT 1 FROM visit WHERE business_date < :date)")
                .param("date", date).query(Boolean.class).single()) {
            days.resetTicketSequences();
        }
        return date;
    }

    @Transactional
    public void resetForOpening() {
        days.resetResourcesForOpening();
        if (calendar.alwaysOpen()) return;
        lockSequences();
        var status = calendar.status();
        // Never rewind numbers that have already been issued for this business day.
        if (status.isOpen() && !hasVisits(status.currentBusinessDate())) days.resetTicketSequences();
    }

    private boolean hasVisits(LocalDate date) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM visit WHERE business_date = :date)")
                .param("date", date).query(Boolean.class).single();
    }

    private void lockSequences() {
        // Every visit creation and reset shares this transaction-scoped database lock.
        jdbc.sql("SELECT pg_advisory_xact_lock(74008001)").query((rs, row) -> 1).single();
    }

}
