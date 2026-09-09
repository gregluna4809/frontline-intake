ALTER TABLE visit ALTER COLUMN visit_type TYPE VARCHAR(20);
ALTER TABLE visit DROP CONSTRAINT visit_visit_type_check;
ALTER TABLE visit ADD CONSTRAINT visit_visit_type_check
    CHECK (visit_type IN ('WALK_IN', 'APPOINTMENT'));
ALTER TABLE visit ADD COLUMN appointment_category VARCHAR(100);
ALTER TABLE visit ALTER COLUMN reason DROP NOT NULL;
ALTER TABLE visit ADD CONSTRAINT visit_intake_check CHECK (
    (visit_type = 'WALK_IN' AND reason IS NOT NULL AND appointment_category IS NULL)
    OR
    (visit_type = 'APPOINTMENT' AND appointment_category IS NOT NULL
        AND length(trim(appointment_category)) > 0)
);

ALTER TABLE visit DROP CONSTRAINT visit_status_check;
ALTER TABLE visit ADD CONSTRAINT visit_status_check CHECK (status IN (
    'CHECKED_IN', 'IDENTIFIED', 'AT_RESOURCE', 'WAITING_RESOURCE', 'WAITING_STAFF', 'CLOSED', 'LEFT'
));

ALTER TABLE visit_event DROP CONSTRAINT visit_event_from_status_check;
ALTER TABLE visit_event ADD CONSTRAINT visit_event_from_status_check CHECK (from_status IN (
    'CHECKED_IN', 'IDENTIFIED', 'AT_RESOURCE', 'WAITING_RESOURCE', 'WAITING_STAFF', 'CLOSED', 'LEFT'
));
ALTER TABLE visit_event DROP CONSTRAINT visit_event_to_status_check;
ALTER TABLE visit_event ADD CONSTRAINT visit_event_to_status_check CHECK (to_status IN (
    'CHECKED_IN', 'IDENTIFIED', 'AT_RESOURCE', 'WAITING_RESOURCE', 'WAITING_STAFF', 'CLOSED', 'LEFT'
));
