CREATE TABLE room (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    active_from DATE,
    active_to DATE,
    sort_order INTEGER NOT NULL
);

INSERT INTO room (code, name, active_from, active_to, sort_order) VALUES
    ('RESOURCE_ROOM', 'Resource Room', NULL, NULL, 1),
    ('ORIENTATION_ROOM', 'Orientation Room', NULL, DATE '2026-11-29', 2),
    ('WORKSHOP_ROOM', 'Workshop Room', DATE '2026-11-30', NULL, 3);

ALTER TABLE resource ADD COLUMN room_id BIGINT REFERENCES room(id);
ALTER TABLE resource ADD COLUMN active_from DATE;
ALTER TABLE resource ADD COLUMN active_to DATE;
UPDATE resource SET room_id = (SELECT id FROM room WHERE code = 'RESOURCE_ROOM');
ALTER TABLE resource ALTER COLUMN room_id SET NOT NULL;

INSERT INTO resource (type, label, status, room_id)
SELECT 'PHONE', 'Phone ' || lpad(n::text, 2, '0'), 'FREE', r.id
FROM room r CROSS JOIN generate_series(7, 7) n WHERE r.code = 'RESOURCE_ROOM';

INSERT INTO resource (type, label, status, room_id)
SELECT 'COMPUTER', 'Computer ' || lpad(n::text, 2, '0'), 'FREE', r.id
FROM room r CROSS JOIN generate_series(9, 31) n WHERE r.code = 'RESOURCE_ROOM';

INSERT INTO resource (type, label, status, room_id, active_to)
SELECT 'PHONE', 'OR-Phone ' || lpad(n::text, 2, '0'), 'FREE', r.id, DATE '2026-11-29'
FROM room r CROSS JOIN generate_series(1, 11) n WHERE r.code = 'ORIENTATION_ROOM';
