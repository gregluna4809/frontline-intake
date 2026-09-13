package com.frontline.business;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketSequenceService {
    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;

    public TicketSequenceService(JdbcClient jdbc, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    @Transactional
    public LocalDate prepareForCreation() {
        lockSequences();
        LocalDate date = calendar.currentBusinessDate();
        // Catch up after downtime and cover arrivals racing the opening cron.
        // With no earlier visits, leave the initial sequence configuration intact.
        if (!hasVisits(date) && jdbc.sql("SELECT EXISTS (SELECT 1 FROM visit WHERE business_date < :date)")
                .param("date", date).query(Boolean.class).single()) {
            resetSequences();
        }
        return date;
    }

    @Transactional
    public void resetForOpening() {
        lockSequences();
        var status = calendar.status();
        // Never rewind numbers that have already been issued for this business day.
        if (status.isOpen() && !hasVisits(status.currentBusinessDate())) resetSequences();
    }

    private boolean hasVisits(LocalDate date) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM visit WHERE business_date = :date)")
                .param("date", date).query(Boolean.class).single();
    }

    private void lockSequences() {
        // Every visit creation and reset shares this transaction-scoped database lock.
        jdbc.sql("SELECT pg_advisory_xact_lock(74008001)").query((rs, row) -> 1).single();
    }

    private void resetSequences() {
        jdbc.sql("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 1").update();
        jdbc.sql("ALTER SEQUENCE appointment_ticket_sequence RESTART WITH 1").update();
    }
}
