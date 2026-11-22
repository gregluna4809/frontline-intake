ALTER TABLE customer ADD COLUMN created_at TIMESTAMPTZ;

WITH bots AS (
INSERT INTO staff (display_name, role, active)
SELECT 'Bot Clerk ' || lpad(n::text, 2, '0'), 'CLERK', TRUE FROM generate_series(1, 2) AS n
UNION ALL
SELECT 'Bot LSR ' || lpad(n::text, 2, '0'), 'LSR', TRUE FROM generate_series(1, 8) AS n
UNION ALL
SELECT 'Bot Supervising LSR 01', 'SUPERVISING_LSR', TRUE
RETURNING id, display_name, role
)

INSERT INTO staff_rotation (staff_id, day_of_week, duty_type)
SELECT s.id, d.day_of_week,
       CASE WHEN s.display_name IN ('Bot LSR 01', 'Bot LSR 02', 'Bot LSR 03', 'Bot LSR 04')
            THEN 'RESOURCE_ROOM' ELSE 'APPOINTMENTS' END
FROM bots s
CROSS JOIN generate_series(1, 7) AS d(day_of_week)
WHERE s.role = 'LSR' AND s.display_name LIKE 'Bot LSR %';
