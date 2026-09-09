package com.frontline.visit;

import com.frontline.model.Visit;
import com.frontline.model.Customer;
import com.frontline.model.VisitEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class VisitRepository {
    private final JdbcClient jdbc;

    public VisitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Visit create(Visit.VisitType visitType, Visit.Reason reason, Visit.AppointmentCategory appointmentCategory) {
        boolean appointment = visitType == Visit.VisitType.APPOINTMENT;
        return jdbc.sql("""
                WITH ticket_number AS (SELECT nextval(CAST(:sequence AS regclass)) AS value)
                INSERT INTO visit (ticket, visit_type, reason, appointment_category, status, created_at, version)
                SELECT :prefix || lpad(value::text, greatest(4, length(value::text)), '0'),
                       :visitType, :reason, :category, 'CHECKED_IN', CURRENT_TIMESTAMP, 0
                FROM ticket_number
                RETURNING *
                """)
                .param("sequence", appointment ? "appointment_ticket_sequence" : "visit_ticket_sequence")
                .param("prefix", appointment ? "A" : "B")
                .param("visitType", visitType.name())
                .param("reason", reason == null ? null : reason.name(), Types.VARCHAR)
                .param("category", appointmentCategory == null ? null : appointmentCategory.name(), Types.VARCHAR)
                .query(this::mapVisit)
                .single();
    }

    public void recordCheckIn(Visit visit) {
        jdbc.sql("""
                INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at)
                VALUES (:visitId, NULL, 'CHECKED_IN', 'KIOSK', :createdAt)
                """)
                .param("visitId", visit.id())
                .param("createdAt", Timestamp.from(visit.createdAt()))
                .update();
    }

    public List<VisitQueueItem> findAll(Visit.Status status) {
        String sql = """
                SELECT v.*, c.display_name, c.language, c.veteran,
                    (SELECT max(e.created_at) FROM visit_event e
                     WHERE e.visit_id = v.id AND e.to_status = v.status
                       AND e.to_status IN ('WAITING_STAFF', 'WAITING_RESOURCE')) AS waiting_since
                FROM visit v LEFT JOIN customer c ON c.id = v.customer_id
                """;
        if (status != null) sql += " WHERE v.status = :status";
        sql += " ORDER BY v.created_at, v.id";
        var query = jdbc.sql(sql);
        if (status != null) query.param("status", status.name());
        return query.query((rs, row) -> {
            Visit visit = mapVisit(rs, row);
            Customer customer = visit.customerId() == null ? null : new Customer(
                    visit.customerId(), rs.getString("display_name"),
                    Customer.Language.valueOf(rs.getString("language")), rs.getBoolean("veteran"));
            Timestamp waitingSince = rs.getTimestamp("waiting_since");
            return new VisitQueueItem(visit, customer,
                    waitingSince == null ? null : waitingSince.toInstant(), visit.requiredResourceType());
        }).list();
    }

    public Optional<Visit> transition(Visit previous, Visit.Status next, Long customerId, Long resourceId) {
        return jdbc.sql("""
                UPDATE visit
                SET status = :next, customer_id = :customerId, resource_id = :resourceId,
                    version = version + 1,
                    closed_at = CASE WHEN :next IN ('CLOSED', 'LEFT') THEN CURRENT_TIMESTAMP ELSE closed_at END
                WHERE id = :id AND version = :version AND status = :previous
                RETURNING *
                """)
                .param("next", next.name())
                .param("customerId", customerId, Types.BIGINT)
                .param("resourceId", resourceId, Types.BIGINT)
                .param("id", previous.id())
                .param("version", previous.version())
                .param("previous", previous.status().name())
                .query(this::mapVisit).optional();
    }

    public void recordTransition(Visit previous, Visit updated) {
        jdbc.sql("""
                INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at)
                VALUES (:visitId, :fromStatus, :toStatus, :actor, CURRENT_TIMESTAMP)
                """)
                .param("visitId", updated.id())
                .param("fromStatus", previous.status().name())
                .param("toStatus", updated.status().name())
                .param("actor", switch (previous.status()) {
                    case WAITING_STAFF -> "APPOINTMENTS";
                    case AT_RESOURCE, WAITING_RESOURCE -> "RESOURCE_ROOM";
                    default -> "FRONT_DESK";
                })
                .update();
    }

    public Optional<Visit> findById(long id) {
        return jdbc.sql("SELECT * FROM visit WHERE id = :id")
                .param("id", id).query(this::mapVisit).optional();
    }

    public List<VisitEvent> findEvents(long visitId) {
        return jdbc.sql("SELECT * FROM visit_event WHERE visit_id = :visitId ORDER BY id")
                .param("visitId", visitId)
                .query((rs, row) -> new VisitEvent(
                        rs.getLong("id"), rs.getLong("visit_id"),
                        rs.getString("from_status") == null ? null
                                : Visit.Status.valueOf(rs.getString("from_status")),
                        Visit.Status.valueOf(rs.getString("to_status")),
                        rs.getString("actor"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private Visit mapVisit(ResultSet rs, int row) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Visit(
                rs.getLong("id"), rs.getString("ticket"),
                Visit.VisitType.valueOf(rs.getString("visit_type")),
                rs.getString("reason") == null ? null : Visit.Reason.valueOf(rs.getString("reason")),
                rs.getString("appointment_category"),
                Visit.Status.valueOf(rs.getString("status")),
                rs.getObject("customer_id", Long.class),
                rs.getObject("resource_id", Long.class),
                rs.getTimestamp("created_at").toInstant(),
                closedAt == null ? null : closedAt.toInstant(),
                rs.getLong("version"));
    }
}
