package com.frontline.report;

import com.frontline.model.Visit;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
    private final JdbcClient jdbc;

    public ReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<DayActivity> activity(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT v.business_date,
                    count(*) FILTER (WHERE v.visit_type = 'WALK_IN' AND v.reason = 'PHONE_ACCESS') AS phone_access,
                    count(*) FILTER (WHERE v.visit_type = 'WALK_IN' AND v.reason = 'COMPUTER_ACCESS') AS computer_access,
                    count(*) FILTER (WHERE v.visit_type = 'WALK_IN' AND v.reason = 'DOCUMENT_SERVICES') AS document_services,
                    count(*) FILTER (WHERE v.visit_type = 'WALK_IN' AND v.reason = 'BENEFITS_ASSISTANCE') AS benefits_assistance,
                    count(*) FILTER (WHERE v.visit_type = 'WALK_IN' AND v.reason = 'JOB_SEARCH') AS job_search,
                    count(*) FILTER (WHERE v.visit_type = 'WALK_IN' AND v.reason = 'OTHER') AS other,
                    count(*) FILTER (WHERE v.visit_type = 'APPOINTMENT' AND v.appointment_category = 'MANDATORY') AS mandatory,
                    count(*) FILTER (WHERE v.visit_type = 'APPOINTMENT' AND v.appointment_category = 'JOB_SEARCH_ASSISTANCE') AS job_search_assistance,
                    count(*) FILTER (WHERE v.visit_type = 'APPOINTMENT') AS scheduled,
                    count(*) FILTER (WHERE v.visit_type = 'APPOINTMENT'
                        AND v.appointment_status IN ('NOT_ATTENDED_FTR', 'FTR_REPORTED')) AS ftr,
                    avg(extract(epoch FROM (resource.reached_at - v.created_at)) / 60.0)
                        FILTER (WHERE v.visit_type = 'WALK_IN') AS resource_wait,
                    count(*) FILTER (WHERE v.visit_type = 'APPOINTMENT' AND v.previous_visit_id IS NOT NULL) AS reschedules
                FROM visit v
                LEFT JOIN LATERAL (
                    SELECT min(e.created_at) AS reached_at FROM visit_event e
                    WHERE e.visit_id = v.id AND e.to_status = 'AT_RESOURCE'
                        AND e.from_status IS DISTINCT FROM e.to_status
                        AND v.visit_type = 'WALK_IN'
                ) resource ON true
                WHERE v.business_date BETWEEN :from AND :to
                GROUP BY v.business_date
                """).param("from", from).param("to", to).query((rs, row) -> {
                    Map<String, Long> volume = emptyVolume();
                    for (String category : volume.keySet()) volume.put(category, rs.getLong(category));
                    var resourceWait = rs.getBigDecimal("resource_wait");
                    return new DayActivity(rs.getObject("business_date", LocalDate.class), volume,
                            rs.getLong("scheduled"), rs.getLong("ftr"),
                            resourceWait == null ? null : resourceWait.doubleValue(), rs.getLong("reschedules"));
                }).list();
    }

    public List<Closures> closures(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT v.business_date AS date,
                    v.assigned_staff_id, s.display_name, count(*) AS closed_count,
                    sum(extract(epoch FROM (v.closed_at - v.created_at)) / 60.0) AS duration_minutes
                FROM visit v LEFT JOIN staff s ON s.id = v.assigned_staff_id
                WHERE v.visit_type = 'APPOINTMENT' AND v.status = 'CLOSED'
                    AND v.closed_at IS NOT NULL AND v.business_date BETWEEN :from AND :to
                GROUP BY date, v.assigned_staff_id, s.display_name
                ORDER BY date, v.assigned_staff_id
                """).param("from", from).param("to", to)
                .query((rs, row) -> new Closures(rs.getObject("date", LocalDate.class),
                        rs.getObject("assigned_staff_id", Long.class), rs.getString("display_name"),
                        rs.getLong("closed_count"), rs.getDouble("duration_minutes"))).list();
    }

    public Map<LocalDate, Long> callOutCounts(LocalDate from, LocalDate to) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        jdbc.sql("SELECT date, count(*) AS count FROM call_out WHERE date BETWEEN :from AND :to GROUP BY date")
                .param("from", from).param("to", to)
                .query((rs, row) -> Map.entry(rs.getObject("date", LocalDate.class), rs.getLong("count")))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    static Map<String, Long> emptyVolume() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (var reason : Visit.Reason.values()) result.put(reason.name(), 0L);
        for (var category : Visit.AppointmentCategory.values()) result.put(category.name(), 0L);
        return result;
    }

    public record DayActivity(LocalDate date, Map<String, Long> volume, long scheduled, long ftr,
                              Double resourceWait, long reschedules) {}
    public record Closures(LocalDate date, Long staffId, String displayName, long count, double durationMinutes) {}
}
