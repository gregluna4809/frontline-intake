package com.frontline.coverage;

import com.frontline.model.CallOut;
import com.frontline.model.StaffDuty;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CoverageRepository {
    private final JdbcClient jdbc;
    private final java.time.Clock clock;

    public CoverageRepository(JdbcClient jdbc, java.time.Clock businessClock) {
        this.jdbc = jdbc;
        this.clock = businessClock;
    }

    public List<StaffDuty> rotation(LocalDate date) {
        return jdbc.sql("""
                SELECT s.id, s.display_name,
                       CASE WHEN c.id IS NOT NULL THEN 'RESOURCE_ROOM' ELSE r.duty_type END AS duty_type
                FROM staff s
                JOIN staff_rotation r ON r.staff_id = s.id AND r.day_of_week = :dayOfWeek
                LEFT JOIN call_out c ON c.staff_id = s.id AND c.date = :date
                WHERE s.role = 'LSR' AND s.active
                ORDER BY s.display_name, s.id
                """)
                .param("dayOfWeek", date.getDayOfWeek().getValue())
                .param("date", date)
                .query((rs, row) -> new StaffDuty(rs.getLong("id"), rs.getString("display_name"),
                        StaffDuty.DutyType.valueOf(rs.getString("duty_type"))))
                .list();
    }

    public CallOut upsert(long staffId, LocalDate date, String reason) {
        return jdbc.sql("""
                INSERT INTO call_out (staff_id, date, reason, created_at)
                VALUES (:staffId, :date, :reason, :businessNow)
                ON CONFLICT (staff_id, date) DO UPDATE SET reason = EXCLUDED.reason
                RETURNING *
                """)
                .param("staffId", staffId).param("date", date).param("reason", reason, Types.VARCHAR).param("businessNow",java.sql.Timestamp.from(clock.instant()))
                .query(this::mapCallOut).single();
    }

    public List<CallOut> callOuts(LocalDate date) {
        return jdbc.sql("SELECT * FROM call_out WHERE date = :date ORDER BY staff_id, id")
                .param("date", date).query(this::mapCallOut).list();
    }

    private CallOut mapCallOut(ResultSet rs, int row) throws SQLException {
        return new CallOut(rs.getLong("id"), rs.getLong("staff_id"), rs.getObject("date", LocalDate.class),
                rs.getString("reason"), rs.getTimestamp("created_at").toInstant());
    }
}
