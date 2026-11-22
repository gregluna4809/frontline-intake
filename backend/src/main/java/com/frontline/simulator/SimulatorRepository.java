package com.frontline.simulator;

import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
@Transactional
public class SimulatorRepository {
    private final JdbcClient jdbc;
    public SimulatorRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public void lock() { jdbc.sql("SELECT pg_advisory_xact_lock(74008001)").query((rs, row) -> 1).single(); }
    public Optional<Day> day(LocalDate date) {
        return jdbc.sql("SELECT * FROM sim_day WHERE business_date = :date").param("date", date).query(Day.class).optional();
    }
    public Day createDay(LocalDate date, long seed, int target) {
        jdbc.sql("INSERT INTO sim_day (business_date, seed, target_arrivals) VALUES (:date, :seed, :target) ON CONFLICT DO NOTHING")
                .param("date", date).param("seed", seed).param("target", target).update();
        return day(date).orElseThrow();
    }
    public void countArrival(LocalDate date) {
        jdbc.sql("UPDATE sim_day SET arrivals_so_far = arrivals_so_far + 1 WHERE business_date = :date").param("date", date).update();
    }
    public long customer(String language, boolean veteran) {
        return jdbc.sql("""
                WITH number AS (SELECT nextval('sim_customer_sequence')::text AS value)
                INSERT INTO customer (display_name, language, veteran)
                SELECT 'Sim Customer ' || lpad(value, greatest(6, length(value)), '0'), :language, :veteran FROM number
                RETURNING id
                """).param("language", language).param("veteran", veteran).query(Long.class).single();
    }
    public void schedule(long visit, String action, Instant due, Long customer, Long session) {
        schedule(visit, action, due, customer, session, null, null);
    }
    public void schedule(long visit, String action, Instant due, Long customer, Long session, Long staff, Long help) {
        jdbc.sql("INSERT INTO sim_task (visit_id, action, due_at, customer_id, resource_session_id, staff_id, help_request_id) VALUES (:visit, :action, :due, :customer, :session, :staff, :help)")
                .param("visit", visit).param("action", action).param("due", Timestamp.from(due))
                .param("customer", customer, Types.BIGINT).param("session", session, Types.BIGINT)
                .param("staff", staff, Types.BIGINT).param("help", help, Types.BIGINT).update();
    }
    public List<Task> due(LocalDate date, Instant now) {
        return jdbc.sql("""
                SELECT t.* FROM sim_task t JOIN visit v ON v.id = t.visit_id
                WHERE t.done_at IS NULL AND t.due_at <= :now AND v.business_date = :date ORDER BY t.due_at, t.id
                """).param("date", date).param("now", Timestamp.from(now)).query(Task.class).list();
    }
    public Optional<Task> task(long id) {
        return jdbc.sql("SELECT * FROM sim_task WHERE id = :id").param("id", id).query(Task.class).optional();
    }
    public void done(long id, Instant now, String outcome) {
        jdbc.sql("UPDATE sim_task SET done_at = :now, outcome = :outcome WHERE id = :id AND done_at IS NULL")
                .param("id", id).param("now", Timestamp.from(now)).param("outcome", outcome).update();
    }
    public Optional<Long> openSession(long visit) {
        return jdbc.sql("SELECT id FROM resource_session WHERE visit_id = :visit AND ended_at IS NULL")
                .param("visit", visit).query(Long.class).optional();
    }
    public boolean hasSessionTask(long session) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sim_task WHERE resource_session_id = :session AND action IN ('END_SESSION', 'LEAVE_EARLY'))")
                .param("session", session).query(Boolean.class).single();
    }
    public void skipWaiting(long visit, Instant now) {
        jdbc.sql("UPDATE sim_task SET done_at = :now, outcome = 'SKIPPED' WHERE visit_id = :visit AND action = 'LEAVE_WAITING' AND done_at IS NULL")
                .param("visit", visit).param("now", Timestamp.from(now)).update();
    }
    public boolean hasWaitingTask(long visit) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sim_task WHERE visit_id = :visit AND action = 'LEAVE_WAITING' AND done_at IS NULL)")
                .param("visit", visit).query(Boolean.class).single();
    }
    public List<Long> engineWalkIns(LocalDate date) {
        return jdbc.sql("""
                SELECT v.id FROM visit v WHERE v.business_date = :date AND v.visit_type = 'WALK_IN'
                  AND v.status NOT IN ('CLOSED', 'LEFT') AND EXISTS
                    (SELECT 1 FROM sim_task t WHERE t.visit_id = v.id AND t.action = 'IDENTIFY_AND_ROUTE')
                ORDER BY v.created_at, v.id
                """).param("date", date).query(Long.class).list();
    }
    public List<Long> engineAppointments(LocalDate date) {
        return jdbc.sql("""
                SELECT v.id FROM visit v WHERE v.business_date = :date AND v.visit_type = 'APPOINTMENT'
                  AND v.status NOT IN ('CLOSED', 'LEFT') AND EXISTS
                    (SELECT 1 FROM sim_task t WHERE t.visit_id = v.id AND t.action = 'IDENTIFY_AND_ROUTE')
                ORDER BY v.created_at, v.id
                """).param("date", date).query(Long.class).list();
    }
    public boolean hasAppointmentTask(long visit) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM sim_task WHERE visit_id = :visit AND action = 'CLOSE_APPOINTMENT' AND done_at IS NULL)")
                .param("visit", visit).query(Boolean.class).single();
    }
    public boolean free(long staff) {
        return jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM sim_task WHERE staff_id = :staff AND action IN ('FINISH_HELP', 'CLOSE_APPOINTMENT') AND done_at IS NULL)")
                .param("staff", staff).query(Boolean.class).single();
    }
    public Optional<Long> freeStaff(LocalDate date, String duty) {
        return jdbc.sql("""
                SELECT s.id FROM staff s JOIN staff_rotation r ON r.staff_id = s.id AND r.day_of_week = :weekday
                LEFT JOIN call_out c ON c.staff_id = s.id AND c.date = :date
                WHERE s.active AND s.role = 'LSR' AND s.display_name NOT LIKE 'Bot %'
                  AND (CASE WHEN c.id IS NOT NULL THEN 'RESOURCE_ROOM' ELSE r.duty_type END) = :duty
                  AND NOT EXISTS (SELECT 1 FROM sim_task t WHERE t.staff_id = s.id
                    AND t.action IN ('FINISH_HELP', 'CLOSE_APPOINTMENT') AND t.done_at IS NULL)
                ORDER BY s.id LIMIT 1
                """).param("date", date).param("weekday", date.getDayOfWeek().getValue()).param("duty", duty).query(Long.class).optional();
    }
    public boolean activeLsr(long staff) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM staff WHERE id = :id AND active AND role = 'LSR' AND display_name NOT LIKE 'Bot %')")
                .param("id", staff).query(Boolean.class).single();
    }
    public Optional<Long> clerk() {
        return jdbc.sql("SELECT id FROM staff WHERE active AND role = 'CLERK' AND display_name NOT LIKE 'Bot %' ORDER BY id LIMIT 1").query(Long.class).optional();
    }
    public long openHelp(LocalDate date) {
        return jdbc.sql("SELECT count(*) FROM help_request WHERE business_date = :date AND closed_at IS NULL")
                .param("date", date).query(Long.class).single();
    }
    public long appointmentsWaiting(LocalDate date) {
        return jdbc.sql("""
                SELECT count(*) FROM visit v WHERE v.business_date = :date AND v.visit_type = 'APPOINTMENT' AND v.status = 'WAITING_STAFF'
                  AND NOT EXISTS (SELECT 1 FROM sim_task t WHERE t.visit_id = v.id AND t.action = 'CLOSE_APPOINTMENT' AND t.done_at IS NULL)
                """).param("date", date).query(Long.class).single();
    }
    public long appointmentsBeingServed(LocalDate date) {
        return jdbc.sql("""
                SELECT count(*) FROM visit v WHERE v.business_date = :date AND v.visit_type = 'APPOINTMENT' AND v.status = 'WAITING_STAFF'
                  AND EXISTS (SELECT 1 FROM sim_task t WHERE t.visit_id = v.id AND t.action = 'CLOSE_APPOINTMENT' AND t.done_at IS NULL)
                """).param("date", date).query(Long.class).single();
    }
    public void swept(LocalDate date, Instant now) {
        jdbc.sql("UPDATE sim_task SET done_at = :now, outcome = 'CLOSED_DAY' WHERE done_at IS NULL AND visit_id IN (SELECT id FROM visit WHERE business_date = :date)")
                .param("date", date).param("now", Timestamp.from(now)).update();
        jdbc.sql("UPDATE sim_day SET swept_at = :now WHERE business_date = :date")
                .param("date", date).param("now", Timestamp.from(now)).update();
    }
    public long openTasks(LocalDate date) {
        return jdbc.sql("SELECT count(*) FROM sim_task t JOIN visit v ON v.id = t.visit_id WHERE v.business_date = :date AND t.done_at IS NULL")
                .param("date", date).query(Long.class).single();
    }
    public List<Switch> switches() { return jdbc.sql("SELECT * FROM sim_switch ORDER BY role").query(Switch.class).list(); }
    public boolean enabled(String role) { return switches().stream().anyMatch(item -> item.role().equals(role) && item.enabled()); }
    public Switch setSwitch(String role, boolean enabled) {
        return jdbc.sql("UPDATE sim_switch SET enabled = :enabled WHERE role = :role RETURNING *")
                .param("role", role).param("enabled", enabled).query(Switch.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulator switch not found."));
    }
    public record Day(LocalDate businessDate, int targetArrivals, long seed, int arrivalsSoFar, Instant sweptAt) {}
    public record Task(long id, long visitId, String action, Instant dueAt, Instant doneAt, String outcome,
                       Long customerId, Long resourceSessionId, Long staffId, Long helpRequestId) {}
    public record Switch(String role, boolean enabled) {}
}
