package com.frontline.visit;

import com.frontline.model.Visit;
import com.frontline.model.Customer;
import com.frontline.model.VisitEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
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

    public Visit create(Visit.VisitType visitType, Visit.Reason reason, Visit.AppointmentCategory appointmentCategory,
                        Visit.AppointmentStatus appointmentStatus, LocalDate businessDate) {
        boolean appointment = visitType == Visit.VisitType.APPOINTMENT;
        return jdbc.sql("""
                WITH ticket_number AS (SELECT nextval(CAST(:sequence AS regclass)) AS value)
                INSERT INTO visit (ticket, visit_type, reason, appointment_category, appointment_type,
                                   appointment_status, status, created_at, version, business_date)
                SELECT :prefix || lpad(value::text, greatest(4, length(value::text)), '0'),
                       :visitType, :reason, :category, NULL, :appointmentStatus, 'CHECKED_IN', CURRENT_TIMESTAMP, 0, :businessDate
                FROM ticket_number
                RETURNING *
                """)
                .param("businessDate", businessDate)
                .param("sequence", appointment ? "appointment_ticket_sequence" : "visit_ticket_sequence")
                .param("prefix", appointment ? "A" : "B")
                .param("visitType", visitType.name())
                .param("reason", reason == null ? null : reason.name(), Types.VARCHAR)
                .param("category", appointmentCategory == null ? null : appointmentCategory.name(), Types.VARCHAR)
                .param("appointmentStatus", appointmentStatus == null ? null : appointmentStatus.name(), Types.VARCHAR)
                .query(this::mapVisit)
                .single();
    }

    public Visit createRescheduled(Visit previous, LocalDate businessDate) {
        return jdbc.sql("""
                WITH ticket_number AS (SELECT nextval('appointment_ticket_sequence') AS value)
                INSERT INTO visit (ticket, visit_type, appointment_category, appointment_type,
                                   appointment_status, status, customer_id, previous_visit_id, created_at, version, business_date)
                SELECT 'A' || lpad(value::text, greatest(4, length(value::text)), '0'),
                       'APPOINTMENT', :category, :appointmentType, 'RESCHEDULED', 'WAITING_STAFF',
                       :customerId, :previousVisitId, CURRENT_TIMESTAMP, 0, :businessDate
                FROM ticket_number
                RETURNING *
                """)
                .param("businessDate", businessDate)
                .param("category", previous.appointmentCategory())
                .param("appointmentType", previous.appointmentType().name())
                .param("customerId", previous.customerId())
                .param("previousVisitId", previous.id())
                .query(this::mapVisit).single();
    }

    public void recordRescheduled(Visit visit) {
        jdbc.sql("""
                INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at, note)
                VALUES (:visitId, NULL, 'WAITING_STAFF', 'APPOINTMENTS', :createdAt, :note)
                """)
                .param("visitId", visit.id()).param("createdAt", Timestamp.from(visit.createdAt()))
                .param("note", "Rescheduled from visit ID: " + visit.previousVisitId() + ".")
                .update();
    }

    public void recordCheckIn(Visit visit) { recordCheckIn(visit, "KIOSK"); }

    public void recordCheckIn(Visit visit, String actor) {
        jdbc.sql("""
                INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at)
                VALUES (:visitId, NULL, 'CHECKED_IN', :actor, :createdAt)
                """)
                .param("visitId", visit.id())
                .param("actor", actor).param("createdAt", Timestamp.from(visit.createdAt()))
                .update();
    }

    public List<VisitQueueItem> findAll(Visit.Status status, LocalDate businessDate) {
        return findAll(status, businessDate, null);
    }

    public List<VisitQueueItem> findAll(Visit.Status status, LocalDate businessDate, Long assignedStaffId) {
        String sql = """
                SELECT v.*, c.display_name, c.language, c.veteran,
                    (SELECT max(e.created_at) FROM visit_event e
                     WHERE e.visit_id = v.id AND e.to_status = v.status
                       AND e.to_status IN ('WAITING_STAFF', 'WAITING_RESOURCE')
                       AND e.from_status IS DISTINCT FROM e.to_status) AS waiting_since
                FROM visit v LEFT JOIN customer c ON c.id = v.customer_id
                WHERE v.business_date = :businessDate
                """;
        if (status != null) sql += " AND v.status = :status";
        if (assignedStaffId != null) sql += " AND v.assigned_staff_id = :assignedStaffId";
        sql += " ORDER BY v.created_at, v.id";
        var query = jdbc.sql(sql).param("businessDate", businessDate);
        if (status != null) query.param("status", status.name());
        if (assignedStaffId != null) query.param("assignedStaffId", assignedStaffId);
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
                    appointment_type = CASE WHEN :next = 'IDENTIFIED' AND visit_type = 'APPOINTMENT'
                        THEN CASE WHEN EXISTS (
                            SELECT 1 FROM visit history WHERE history.customer_id = :customerId
                                AND history.visit_type = 'APPOINTMENT' AND history.id <> :id
                        ) THEN 'FOLLOWUP' ELSE 'INITIAL' END ELSE appointment_type END,
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

    public Optional<Visit> assignStaff(Visit previous, long staffId) {
        return jdbc.sql("""
                UPDATE visit SET assigned_staff_id = :staffId, version = version + 1
                WHERE id = :id AND version = :version AND status = 'WAITING_STAFF'
                RETURNING *
                """)
                .param("staffId", staffId)
                .param("id", previous.id())
                .param("version", previous.version())
                .query(this::mapVisit).optional();
    }

    public void recordTransition(Visit previous, Visit updated) {
        recordTransition(previous, updated, staffAssignmentNote(previous, updated));
    }

    public void recordTransition(Visit previous, Visit updated, String note) {
        recordTransition(previous, updated, note, switch (previous.status()) {
            case WAITING_STAFF -> "APPOINTMENTS";
            case AT_RESOURCE, WAITING_RESOURCE -> "RESOURCE_ROOM";
            default -> "FRONT_DESK";
        });
    }

    public void recordTransition(Visit previous, Visit updated, String note, String actor) {
        jdbc.sql("""
                INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at, note)
                VALUES (:visitId, :fromStatus, :toStatus, :actor, CURRENT_TIMESTAMP, :note)
                """)
                .param("visitId", updated.id())
                .param("note", note, Types.VARCHAR)
                .param("fromStatus", previous.status().name())
                .param("toStatus", updated.status().name())
                .param("actor", actor)
                .update();
    }

    private String staffAssignmentNote(Visit previous, Visit updated) {
        if (previous.status() != Visit.Status.WAITING_STAFF || updated.status() != Visit.Status.WAITING_STAFF)
            return null;
        return previous.assignedStaffId() == null
                ? "Assigned staff ID: " + updated.assignedStaffId() + "."
                : "Reassigned staff from ID: " + previous.assignedStaffId()
                    + " to ID: " + updated.assignedStaffId() + ".";
    }

    public void closeMissedAppointments(LocalDate businessDate) {
        // Capture the previous status for each event in the same SQL statement.
        jdbc.sql("""
                WITH candidates AS (
                    SELECT id, status FROM visit
                    WHERE visit_type = 'APPOINTMENT' AND appointment_status = 'SCHEDULED'
                      AND business_date < :businessDate AND status NOT IN ('CLOSED', 'LEFT')
                    FOR UPDATE
                ), updated AS (
                    UPDATE visit v SET appointment_status = 'NOT_ATTENDED_FTR', status = 'CLOSED',
                        closed_at = CURRENT_TIMESTAMP, version = v.version + 1
                    FROM candidates c WHERE v.id = c.id
                    RETURNING v.id, c.status AS previous_status
                )
                INSERT INTO visit_event (visit_id, from_status, to_status, actor, created_at, note)
                SELECT id, previous_status, 'CLOSED', 'SYSTEM', CURRENT_TIMESTAMP,
                    'Automatic FTR at business-day rollover: SCHEDULED -> NOT_ATTENDED_FTR.'
                FROM updated
                """).param("businessDate", businessDate).update();
    }

    public Optional<Visit> reportFtr(Visit previous) {
        return jdbc.sql("""
                UPDATE visit SET appointment_status = 'FTR_REPORTED', version = version + 1
                WHERE id = :id AND version = :version AND appointment_status = 'NOT_ATTENDED_FTR'
                RETURNING *
                """).param("id", previous.id()).param("version", previous.version())
                .query(this::mapVisit).optional();
    }

    public Optional<Visit> latestOtherAppointment(long customerId, long currentVisitId) {
        return jdbc.sql("""
                SELECT * FROM visit WHERE customer_id = :customerId AND id <> :currentVisitId
                    AND visit_type = 'APPOINTMENT'
                ORDER BY created_at DESC, id DESC LIMIT 1
                """).param("customerId", customerId).param("currentVisitId", currentVisitId)
                .query(this::mapVisit).optional();
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
                        rs.getString("actor"), rs.getTimestamp("created_at").toInstant(), rs.getString("note")))
                .list();
    }

    private Visit mapVisit(ResultSet rs, int row) throws SQLException {
        Timestamp closedAt = rs.getTimestamp("closed_at");
        return new Visit(
                rs.getLong("id"), rs.getString("ticket"),
                Visit.VisitType.valueOf(rs.getString("visit_type")),
                rs.getString("reason") == null ? null : Visit.Reason.valueOf(rs.getString("reason")),
                rs.getString("appointment_category"),
                rs.getString("appointment_type") == null ? null
                        : Visit.AppointmentType.valueOf(rs.getString("appointment_type")),
                rs.getString("appointment_status") == null ? null
                        : Visit.AppointmentStatus.valueOf(rs.getString("appointment_status")),
                rs.getObject("assigned_staff_id", Long.class),
                Visit.Status.valueOf(rs.getString("status")),
                rs.getObject("customer_id", Long.class),
                rs.getObject("resource_id", Long.class),
                rs.getTimestamp("created_at").toInstant(),
                closedAt == null ? null : closedAt.toInstant(),
                rs.getLong("version"), rs.getObject("previous_visit_id", Long.class),
                rs.getObject("business_date", LocalDate.class));
    }
}
