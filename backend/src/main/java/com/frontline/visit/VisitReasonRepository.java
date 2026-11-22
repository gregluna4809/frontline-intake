package com.frontline.visit;

import com.frontline.model.Visit;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class VisitReasonRepository {
    private final JdbcClient jdbc;
    public VisitReasonRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Reason> active(LocalDate date) {
        return jdbc.sql("""
                SELECT code, label FROM visit_reason
                WHERE (active_from IS NULL OR active_from <= :date)
                  AND (active_to IS NULL OR active_to >= :date)
                ORDER BY sort_order
                """).param("date", date).query(Reason.class).list();
    }

    public void requireActive(Visit.Reason reason, LocalDate date) {
        if (reason != null && active(date).stream().noneMatch(item -> item.code().equals(reason.name()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reason is not active on the visit's business date.");
        }
    }
    public record Reason(String code, String label) {}
}
