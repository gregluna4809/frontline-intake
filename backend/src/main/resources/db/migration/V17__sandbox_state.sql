CREATE TABLE sandbox_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    anchor_sandbox TIMESTAMPTZ,
    anchor_real TIMESTAMPTZ,
    speed INTEGER NOT NULL DEFAULT 5 CHECK (speed IN (1, 5, 10)),
    scenario VARCHAR(30) NOT NULL DEFAULT 'SHIFT_TO_ONLINE',
    day_started_at TIMESTAMPTZ,
    warming BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO sandbox_state (id) VALUES (1);
