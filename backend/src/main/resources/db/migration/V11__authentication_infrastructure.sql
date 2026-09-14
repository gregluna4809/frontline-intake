ALTER TABLE staff DROP CONSTRAINT staff_role_check;
ALTER TABLE staff ADD CONSTRAINT staff_role_check
    CHECK (role IN ('LSR', 'SUPERVISING_LSR', 'CLERK', 'CENTER_MANAGER', 'ADMINISTRATOR'));

INSERT INTO staff (display_name, role) VALUES
    ('Demo Clerk 001', 'CLERK'),
    ('Demo Clerk 002', 'CLERK'),
    ('Demo Clerk 003', 'CLERK'),
    ('Demo Center Manager 001', 'CENTER_MANAGER'),
    ('Demo Administrator 001', 'ADMINISTRATOR');

CREATE TABLE user_account (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    staff_id BIGINT NOT NULL UNIQUE REFERENCES staff(id),
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Synthetic demo only: all accounts use demo1234 (BCrypt, cost 10).
-- IDs make usernames deterministic and unique, including staff added before this migration.
INSERT INTO user_account (staff_id, username, password_hash)
SELECT id, 'staff' || id, '$2a$10$cHjC2Ts/AYJ6lAh3fdFcv.Y6mg5RTx3PQRJ8vHLEHNhb8KLyFPhte'
FROM staff ORDER BY id;
