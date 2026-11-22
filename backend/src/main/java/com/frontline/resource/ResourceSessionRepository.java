package com.frontline.resource;

import com.frontline.model.Visit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceSessionRepository {
    private final JdbcClient jdbc;
    private final java.time.Clock clock;
    public ResourceSessionRepository(JdbcClient jdbc, java.time.Clock businessClock) { this.jdbc = jdbc; this.clock = businessClock; }

    public void open(Visit visit, long resourceId) {
        jdbc.sql("""
                INSERT INTO resource_session (visit_id, resource_id, business_date, started_at)
                VALUES (:visit, :resource, :date, :businessNow)
                """).param("visit", visit.id()).param("resource", resourceId)
                .param("date", visit.businessDate()).param("businessNow",java.sql.Timestamp.from(clock.instant())).update();
    }

    public void end(long visitId, String reason) {
        jdbc.sql("""
                UPDATE resource_session SET ended_at = :businessNow, end_reason = :reason
                WHERE visit_id = :visit AND ended_at IS NULL
                """).param("visit", visitId).param("reason", reason).param("businessNow",java.sql.Timestamp.from(clock.instant())).update();
        if (reason.equals("CLOSED") || reason.equals("LEFT")) {
            jdbc.sql("""
                    UPDATE help_request SET closed_at = :businessNow, outcome = 'VISIT_ENDED', version = version + 1
                    WHERE visit_id = :visit AND closed_at IS NULL
                    """).param("visit", visitId).param("businessNow",java.sql.Timestamp.from(clock.instant())).update();
        }
    }

    public void rollover() {
        var now = java.sql.Timestamp.from(clock.instant());
        jdbc.sql("UPDATE help_request SET closed_at = :now, outcome = 'EXPIRED', version = version + 1 WHERE closed_at IS NULL").param("now",now).update();
        jdbc.sql("UPDATE resource_session SET ended_at = :now, end_reason = 'ROLLOVER' WHERE ended_at IS NULL").param("now",now).update();
    }
}
