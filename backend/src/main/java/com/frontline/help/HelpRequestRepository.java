package com.frontline.help;

import com.frontline.model.HelpRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HelpRequestRepository {
    private final JdbcClient jdbc;
    public HelpRequestRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    private static final String SELECT = """
            SELECT h.*, v.ticket, r.id AS resource_id, r.label AS resource_label,
                   room.code AS room_code, room.name AS room_name,
                   raised.display_name AS raised_by_name, claimed.display_name AS claimed_by_name,
                   greatest(0, extract(epoch FROM (coalesce(h.closed_at, CURRENT_TIMESTAMP) - h.raised_at)))::bigint AS age_seconds
            FROM help_request h JOIN visit v ON v.id = h.visit_id
            JOIN resource_session s ON s.id = h.session_id
            JOIN resource r ON r.id = s.resource_id JOIN room ON room.id = r.room_id
            JOIN staff raised ON raised.id = h.raised_by_staff_id
            LEFT JOIN staff claimed ON claimed.id = h.claimed_by_staff_id
            """;

    public Optional<HelpRequest> find(long id) {
        return jdbc.sql(SELECT + " WHERE h.id = :id").param("id", id).query(HelpRequest.class).optional();
    }
    public List<HelpRequest> list(LocalDate date, boolean open) {
        return jdbc.sql(SELECT + " WHERE h.business_date = :date" + (open ? " AND h.closed_at IS NULL" : "")
                + " ORDER BY h.raised_at, h.id").param("date", date).query(HelpRequest.class).list();
    }
    public boolean hasOpen(long visitId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM help_request WHERE visit_id = :visit AND closed_at IS NULL)")
                .param("visit", visitId).query(Boolean.class).single();
    }
    public Optional<Long> computerSession(long visitId) {
        return jdbc.sql("""
                SELECT s.id FROM resource_session s JOIN resource r ON r.id = s.resource_id
                WHERE s.visit_id = :visit AND s.ended_at IS NULL AND r.type = 'COMPUTER'
                  AND r.active_visit_id = :visit AND r.status = 'OCCUPIED'
                """).param("visit", visitId).query(Long.class).optional();
    }
    public long raise(long visitId, long sessionId, LocalDate date, long staffId) {
        return jdbc.sql("""
                INSERT INTO help_request (visit_id, session_id, business_date, raised_at, raised_by_staff_id)
                VALUES (:visit, :session, :date, CURRENT_TIMESTAMP, :staff) RETURNING id
                """).param("visit", visitId).param("session", sessionId).param("date", date)
                .param("staff", staffId).query(Long.class).single();
    }
    public void claim(long id, long staffId) {
        jdbc.sql("UPDATE help_request SET claimed_by_staff_id = :staff, claimed_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = :id")
                .param("id", id).param("staff", staffId).update();
    }
    public void close(long id, long staffId, HelpRequest.Outcome outcome) {
        jdbc.sql("UPDATE help_request SET closed_by_staff_id = :staff, closed_at = CURRENT_TIMESTAMP, outcome = :outcome, version = version + 1 WHERE id = :id")
                .param("id", id).param("staff", staffId).param("outcome", outcome.name()).update();
    }
}
