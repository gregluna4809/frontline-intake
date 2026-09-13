CREATE TABLE business_day_snapshot (
    business_date DATE PRIMARY KEY,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload JSONB NOT NULL
);
CREATE TABLE business_day_rollover (
    business_date DATE PRIMARY KEY
);
ALTER TABLE resource ADD COLUMN active_visit_id BIGINT REFERENCES visit(id);
UPDATE resource r SET active_visit_id = (
    SELECT v.id FROM visit v WHERE v.resource_id = r.id AND v.status = 'AT_RESOURCE'
    ORDER BY v.business_date DESC, v.created_at DESC, v.id DESC LIMIT 1
) WHERE r.status = 'OCCUPIED';
-- Historical AT_RESOURCE visits survive rollover; ownership now lives on resource.
DROP INDEX visit_active_resource_idx;
CREATE UNIQUE INDEX resource_active_visit_idx ON resource(active_visit_id) WHERE active_visit_id IS NOT NULL;
