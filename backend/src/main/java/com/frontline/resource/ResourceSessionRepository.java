package com.frontline.resource;

import com.frontline.model.Visit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ResourceSessionRepository {
    private final JdbcClient jdbc;
    public ResourceSessionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void open(Visit visit, long resourceId) {
        jdbc.sql("""
                INSERT INTO resource_session (visit_id, resource_id, business_date, started_at)
                VALUES (:visit, :resource, :date, CURRENT_TIMESTAMP)
                """).param("visit", visit.id()).param("resource", resourceId)
                .param("date", visit.businessDate()).update();
    }

    public void end(long visitId, String reason) {
        jdbc.sql("""
                UPDATE resource_session SET ended_at = CURRENT_TIMESTAMP, end_reason = :reason
                WHERE visit_id = :visit AND ended_at IS NULL
                """).param("visit", visitId).param("reason", reason).update();
        if (reason.equals("CLOSED") || reason.equals("LEFT")) {
            jdbc.sql("""
                    UPDATE help_request SET closed_at = CURRENT_TIMESTAMP, outcome = 'VISIT_ENDED', version = version + 1
                    WHERE visit_id = :visit AND closed_at IS NULL
                    """).param("visit", visitId).update();
        }
    }

    public void rollover() {
        jdbc.sql("UPDATE help_request SET closed_at = CURRENT_TIMESTAMP, outcome = 'EXPIRED', version = version + 1 WHERE closed_at IS NULL").update();
        jdbc.sql("UPDATE resource_session SET ended_at = CURRENT_TIMESTAMP, end_reason = 'ROLLOVER' WHERE ended_at IS NULL").update();
    }
}
