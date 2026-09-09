package com.frontline.visit;

import com.frontline.model.Visit;
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

    public Visit create(Visit.Reason reason) {
        return jdbc.sql("""
                WITH ticket_number AS (SELECT nextval('visit_ticket_sequence') AS value)
                INSERT INTO visit (ticket, visit_type, reason, status, created_at, version)
                SELECT 'B' || lpad(value::text, greatest(4, length(value::text)), '0'),
                       'WALK_IN', :reason, 'CHECKED_IN', CURRENT_TIMESTAMP, 0
                FROM ticket_number
                RETURNING *
                """)
                .param("reason", reason.name())
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

    public List<Visit> findAll(Visit.Status status) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM visit ORDER BY created_at, id")
                    .query(this::mapVisit).list();
        }
        return jdbc.sql("SELECT * FROM visit WHERE status = :status ORDER BY created_at, id")
                .param("status", status.name())
                .query(this::mapVisit).list();
    }

    public Optional<Visit> transition(Visit previous, Visit.Status next, Long customerId, Long resourceId) {
        return jdbc.sql("""
                UPDATE visit
                SET status = :next, customer_id = :customerId, resource_id = :resourceId,
                    version = version + 1
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
                VALUES (:visitId, :fromStatus, :toStatus, 'FRONT_DESK', CURRENT_TIMESTAMP)
                """)
                .param("visitId", updated.id())
                .param("fromStatus", previous.status().name())
                .param("toStatus", updated.status().name())
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
