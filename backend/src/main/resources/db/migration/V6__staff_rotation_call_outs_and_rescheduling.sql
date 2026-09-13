CREATE TABLE staff_rotation (
    staff_id BIGINT NOT NULL REFERENCES staff(id),
    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    duty_type VARCHAR(20) NOT NULL CHECK (duty_type IN ('APPOINTMENTS', 'RESOURCE_ROOM')),
    PRIMARY KEY (staff_id, day_of_week)
);

-- ISO weekdays: Monday = 1 through Sunday = 7. One staggered Resource Room day per LSR.
INSERT INTO staff_rotation (staff_id, day_of_week, duty_type)
SELECT s.id, d.day_of_week,
       CASE WHEN d.day_of_week = s.rotation_day THEN 'RESOURCE_ROOM' ELSE 'APPOINTMENTS' END
FROM (SELECT id, row_number() OVER (ORDER BY id) AS rotation_day FROM staff WHERE role = 'LSR') s
CROSS JOIN generate_series(1, 7) AS d(day_of_week);

CREATE TABLE call_out (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    staff_id BIGINT NOT NULL REFERENCES staff(id),
    date DATE NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (staff_id, date)
);
CREATE INDEX call_out_date_idx ON call_out(date, staff_id);

ALTER TABLE visit ADD COLUMN previous_visit_id BIGINT UNIQUE REFERENCES visit(id);
ALTER TABLE visit ADD CONSTRAINT visit_previous_not_self_check
    CHECK (previous_visit_id IS NULL OR previous_visit_id <> id);
