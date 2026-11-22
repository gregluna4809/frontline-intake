package com.frontline.sandbox;

import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.frontline.simulator.SimulatorProperties.Scenario;

@Repository
public class SandboxStateRepository {
    private final JdbcClient jdbc;
    public SandboxStateRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public State state() { return jdbc.sql("SELECT * FROM sandbox_state WHERE id = 1").query(State.class).single(); }
    public State lock() { return jdbc.sql("SELECT * FROM sandbox_state WHERE id = 1 FOR UPDATE").query(State.class).single(); }
    public void begin(Instant sandbox, Instant real, int speed, Scenario scenario) {
        jdbc.sql("UPDATE sandbox_state SET anchor_sandbox = :sandbox, anchor_real = :real, speed = :speed, scenario = :scenario, day_started_at = :real, warming = TRUE WHERE id = 1")
                .param("sandbox",Timestamp.from(sandbox)).param("real",Timestamp.from(real)).param("speed",speed).param("scenario",scenario.name()).update();
    }
    public void finish(Instant sandbox, Instant real, int speed, Scenario scenario) {
        jdbc.sql("UPDATE sandbox_state SET anchor_sandbox = :sandbox, anchor_real = :real, speed = :speed, scenario = :scenario, warming = FALSE WHERE id = 1")
                .param("sandbox",Timestamp.from(sandbox)).param("real",Timestamp.from(real)).param("speed",speed).param("scenario",scenario.name()).update();
    }
    public record State(int id, Instant anchorSandbox, Instant anchorReal, int speed, Scenario scenario, Instant dayStartedAt, boolean warming) {}
}
