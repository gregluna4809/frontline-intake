package com.frontline.retention;

import com.frontline.business.BusinessCalendar;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RetentionJob {
    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;
    private final int days;

    public RetentionJob(JdbcClient jdbc, BusinessCalendar calendar,
                        @Value("${frontline.retention.days:14}") int days) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.days = days;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = BusinessCalendar.TIME_ZONE)
    @Transactional
    public void purge() {
        jdbc.sql("SELECT pg_advisory_xact_lock(74008001)").query((rs, row) -> 1).single();
        LocalDate cutoff = calendar.calendarDate().minusDays(days);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("sim_task", update("""
                DELETE FROM sim_task t USING visit v
                WHERE t.visit_id = v.id AND v.business_date < :cutoff
                """, cutoff));
        counts.put("help_request", update("DELETE FROM help_request WHERE business_date < :cutoff", cutoff));
        counts.put("resource_session", update("DELETE FROM resource_session WHERE business_date < :cutoff", cutoff));
        counts.put("visit_event", update("""
                DELETE FROM visit_event e USING visit v
                WHERE e.visit_id = v.id AND v.business_date < :cutoff
                """, cutoff));
        counts.put("resource", update("""
                UPDATE resource r SET active_visit_id = NULL, status = 'FREE'
                FROM visit v WHERE r.active_visit_id = v.id AND v.business_date < :cutoff
                """, cutoff));
        int linksCleared = update("""
                UPDATE visit retained SET previous_visit_id = NULL, version = retained.version + 1
                FROM visit expired WHERE retained.previous_visit_id = expired.id
                  AND retained.business_date >= :cutoff AND expired.business_date < :cutoff
                """, cutoff);
        counts.put("visit", update("DELETE FROM visit WHERE business_date < :cutoff", cutoff));
        counts.put("call_out", update("DELETE FROM call_out WHERE date < :cutoff", cutoff));
        counts.put("business_day_snapshot", update("DELETE FROM business_day_snapshot WHERE business_date < :cutoff", cutoff));
        counts.put("business_day_rollover", update("DELETE FROM business_day_rollover WHERE business_date < :cutoff", cutoff));
        counts.put("sim_day", update("DELETE FROM sim_day WHERE business_date < :cutoff", cutoff));
        counts.put("customer", jdbc.sql("""
                DELETE FROM customer c WHERE c.created_at < :cutoff
                  AND NOT EXISTS (SELECT 1 FROM visit v WHERE v.customer_id = c.id)
                """).param("cutoff", Timestamp.from(cutoff.atStartOfDay(BusinessCalendar.ZONE).toInstant())).update());
        log.info("Retention before {} affected rows {} and cleared {} previous visit links", cutoff, counts, linksCleared);
    }

    private int update(String sql, LocalDate cutoff) {
        return jdbc.sql(sql).param("cutoff", cutoff).update();
    }
}
