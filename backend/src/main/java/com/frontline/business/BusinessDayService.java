package com.frontline.business;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frontline.coverage.CoverageRepository;
import com.frontline.resource.ResourceRepository;
import com.frontline.staff.StaffRepository;
import com.frontline.visit.VisitRepository;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BusinessDayService {
    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;
    private final VisitRepository visits;
    private final ResourceRepository resources;
    private final StaffRepository staff;
    private final CoverageRepository coverage;
    private final ObjectMapper mapper;
    private final com.frontline.resource.ResourceSessionRepository sessions;
    private final java.time.Clock clock;

    public BusinessDayService(JdbcClient jdbc, BusinessCalendar calendar, VisitRepository visits,
                              ResourceRepository resources, StaffRepository staff,
                              CoverageRepository coverage, ObjectMapper mapper,
                              com.frontline.resource.ResourceSessionRepository sessions, java.time.Clock businessClock) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.visits = visits;
        this.resources = resources;
        this.staff = staff;
        this.coverage = coverage;
        this.mapper = mapper;
        this.sessions = sessions;
        this.clock = businessClock;
    }

    // Called before mutations, opening reset, and scheduled close. The same lock
    // as ticket creation makes capture/reset atomic with visit and coverage changes.
    @Transactional
    public void freezeCompletedDays() {
        jdbc.sql("SELECT pg_advisory_xact_lock(74008001)").query((rs, row) -> 1).single();
        LocalDate completed = calendar.lastCompletedBusinessDate();
        var dates = new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT business_date FROM visit
                WHERE business_date <= :date AND business_date NOT IN
                    (SELECT business_date FROM business_day_snapshot)
                ORDER BY business_date
                """).param("date", completed).query(LocalDate.class).list());
        dates.add(completed);
        for (LocalDate date : dates) capture(date);
    }

    @Transactional
    public void resetResourcesForOpening() {
        freezeCompletedDays();
        if (!calendar.isOpenNow()) return;
        LocalDate date = calendar.currentBusinessDate();
        int inserted = jdbc.sql("""
                INSERT INTO business_day_rollover (business_date) VALUES (:date)
                ON CONFLICT DO NOTHING
                """).param("date", date).update();
        if (inserted == 1) {
            // Preserve closing snapshots first, then detect FTR before resources/tickets reset.
            visits.closeMissedAppointments(date);
            sessions.rollover();
            if (calendar.alwaysOpen()) resetTicketSequences();
            jdbc.sql("UPDATE resource SET status = 'FREE', active_visit_id = NULL").update();
        }
    }

    @Transactional
    public JsonNode snapshot(LocalDate requested) {
        freezeCompletedDays();
        LocalDate date = requested == null ? calendar.lastCompletedBusinessDate() : requested;
        boolean saved = jdbc.sql("SELECT EXISTS (SELECT 1 FROM business_day_snapshot WHERE business_date = :date)")
                .param("date", date).query(Boolean.class).single();
        if (!saved && (!calendar.isBusinessDay(date) || date.isAfter(calendar.lastCompletedBusinessDate()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a completed business date.");
        }
        capture(date);
        String json = jdbc.sql("SELECT payload::text FROM business_day_snapshot WHERE business_date = :date")
                .param("date", date).query(String.class).single();
        try { return mapper.readTree(json); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot read snapshot.", exception); }
    }

    void resetTicketSequences() {
        jdbc.sql("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 1").update();
        jdbc.sql("ALTER SEQUENCE appointment_ticket_sequence RESTART WITH 1").update();
    }

    private void capture(LocalDate date) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM business_day_snapshot WHERE business_date = :date)")
                .param("date", date).query(Boolean.class).single();
        if (exists) return;
        // Occupancy belongs to this day's visits, not the next day's resource state.
        var inventory = date.equals(calendar.lastCompletedBusinessDate())
                ? resources.inventory(date) : resources.snapshotInventory(date);
        var payload = Map.of("businessDate", date, "visits", visits.findAll(null, date),
                "resources", inventory, "staff", staff.findAll(),
                "coverage", coverage.rotation(date), "callOuts", coverage.callOuts(date));
        try {
            jdbc.sql("INSERT INTO business_day_snapshot (business_date, captured_at, payload) VALUES (:date, :now, CAST(:payload AS jsonb))")
                    .param("date", date).param("now",java.sql.Timestamp.from(clock.instant())).param("payload", mapper.writeValueAsString(payload)).update();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot save snapshot.", exception);
        }
    }
}
