CREATE TABLE sim_day (
    business_date DATE PRIMARY KEY,
    target_arrivals INTEGER NOT NULL,
    seed BIGINT NOT NULL,
    arrivals_so_far INTEGER NOT NULL DEFAULT 0,
    swept_at TIMESTAMPTZ
);
CREATE TABLE sim_task (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit(id),
    action VARCHAR(30) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    done_at TIMESTAMPTZ,
    outcome VARCHAR(30),
    customer_id BIGINT REFERENCES customer(id),
    resource_session_id BIGINT REFERENCES resource_session(id)
);
CREATE INDEX sim_task_due_idx ON sim_task(done_at, due_at);
CREATE TABLE sim_switch (role VARCHAR(30) PRIMARY KEY, enabled BOOLEAN NOT NULL);
INSERT INTO sim_switch (role, enabled) VALUES
    ('ARRIVALS', TRUE), ('CLERKS', TRUE), ('RR_LSRS', TRUE), ('APPOINTMENT_LSRS', TRUE);
CREATE SEQUENCE sim_customer_sequence START WITH 1;
