package com.frontline.sandbox;

import com.frontline.simulator.SimulatorProperties.Scenario;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SandboxPreparation {
    private final SandboxStateRepository state;
    private final JdbcClient jdbc;
    public SandboxPreparation(SandboxStateRepository state, JdbcClient jdbc) { this.state = state; this.jdbc = jdbc; }
    @Transactional
    public void prepare(Instant opening, Instant real, int speed, Scenario scenario, boolean bypassRateLimit) {
        var previous = state.lock();
        if (previous.warming() || (!bypassRateLimit && previous.dayStartedAt() != null && real.isBefore(previous.dayStartedAt().plusSeconds(60))))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"A sandbox day can be started once per 60 seconds.");
        jdbc.sql("SELECT pg_advisory_xact_lock(74008001)").query((rs,row) -> 1).single();
        jdbc.sql("UPDATE resource SET status = 'FREE', active_visit_id = NULL").update();
        for (String table : java.util.List.of("sim_task","help_request","resource_session","visit_event","visit","sim_day","call_out","business_day_snapshot","business_day_rollover"))
            jdbc.sql("DELETE FROM " + table).update();
        jdbc.sql("DELETE FROM customer WHERE display_name LIKE 'Sim Customer %'").update();
        jdbc.sql("ALTER SEQUENCE visit_ticket_sequence RESTART WITH 1").update();
        jdbc.sql("ALTER SEQUENCE appointment_ticket_sequence RESTART WITH 1").update();
        state.begin(opening,real,speed,scenario);
    }
}
