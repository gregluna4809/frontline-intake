ALTER TABLE visit DROP CONSTRAINT visit_appointment_type_intake_check;
ALTER TABLE visit ADD CONSTRAINT visit_appointment_type_intake_check
    CHECK (visit_type = 'APPOINTMENT' OR appointment_type IS NULL);

-- All legacy synthetic categories map to Mandatory, including pre-enum labels.
UPDATE visit SET appointment_category = 'MANDATORY' WHERE visit_type = 'APPOINTMENT';
ALTER TABLE visit ADD CONSTRAINT visit_appointment_category_check
    CHECK (appointment_category IS NULL OR appointment_category IN ('MANDATORY', 'JOB_SEARCH_ASSISTANCE'));
