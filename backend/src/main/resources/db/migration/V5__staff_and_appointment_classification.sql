CREATE TABLE staff (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    role VARCHAR(30) NOT NULL CHECK (role IN ('LSR', 'SUPERVISING_LSR'))
);

INSERT INTO staff (display_name, role)
SELECT 'Demo Staff ' || lpad(n::text, 3, '0'), 'LSR'
FROM generate_series(1, 4) AS n;
INSERT INTO staff (display_name, role) VALUES ('Demo Supervisor 001', 'SUPERVISING_LSR');

ALTER TABLE visit ADD COLUMN appointment_type TEXT
    CHECK (appointment_type IN ('INITIAL', 'FOLLOWUP'));
ALTER TABLE visit ADD COLUMN appointment_status TEXT
    CHECK (appointment_status IN ('SCHEDULED', 'RESCHEDULED', 'NOT_ATTENDED_FTR', 'FTR_REPORTED'));
ALTER TABLE visit ADD COLUMN assigned_staff_id BIGINT REFERENCES staff(id);

-- Existing synthetic appointments predate classification.
UPDATE visit SET appointment_type = 'INITIAL', appointment_status = 'SCHEDULED'
WHERE visit_type = 'APPOINTMENT';

ALTER TABLE visit ADD CONSTRAINT visit_appointment_type_intake_check CHECK (
    (visit_type = 'APPOINTMENT' AND appointment_type IS NOT NULL)
    OR (visit_type = 'WALK_IN' AND appointment_type IS NULL)
);
ALTER TABLE visit_event ADD COLUMN note TEXT;
