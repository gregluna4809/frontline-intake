CREATE TABLE visit_reason (
    code VARCHAR(30) PRIMARY KEY,
    label VARCHAR(100) NOT NULL,
    sort_order INTEGER NOT NULL,
    active_from DATE,
    active_to DATE
);
INSERT INTO visit_reason (code, label, sort_order, active_from, active_to) VALUES
    ('PHONE_ACCESS', 'Phone Access', 1, NULL, DATE '2026-11-29'),
    ('UI_ACCOUNT', 'UI account help', 2, DATE '2026-11-30', NULL),
    ('COMPUTER_ACCESS', 'Computer Access', 3, NULL, NULL),
    ('DOCUMENT_SERVICES', 'Document Services', 4, NULL, NULL),
    ('BENEFITS_ASSISTANCE', 'Benefits Assistance', 5, NULL, NULL),
    ('JOB_SEARCH', 'Job Search', 6, NULL, NULL),
    ('OTHER', 'Other', 7, NULL, NULL);
ALTER TABLE visit DROP CONSTRAINT visit_reason_check;
ALTER TABLE visit ADD CONSTRAINT visit_reason_check CHECK (reason IN (
    'PHONE_ACCESS', 'UI_ACCOUNT', 'COMPUTER_ACCESS', 'DOCUMENT_SERVICES',
    'BENEFITS_ASSISTANCE', 'JOB_SEARCH', 'OTHER'
));
ALTER TABLE visit ADD CONSTRAINT visit_reason_fk FOREIGN KEY (reason) REFERENCES visit_reason(code);
ALTER TABLE visit ADD COLUMN requested_resource_type VARCHAR(10)
    CHECK (requested_resource_type IN ('PHONE', 'COMPUTER', 'STATION'));

CREATE TABLE resource_session (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit(id),
    resource_id BIGINT NOT NULL REFERENCES resource(id),
    business_date DATE NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    end_reason VARCHAR(20) CHECK (end_reason IN ('CLOSED', 'LEFT', 'TO_PHONE', 'ROLLOVER'))
);
CREATE UNIQUE INDEX resource_session_open_visit_idx ON resource_session(visit_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX resource_session_open_resource_idx ON resource_session(resource_id) WHERE ended_at IS NULL;

CREATE TABLE help_request (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit(id),
    session_id BIGINT NOT NULL REFERENCES resource_session(id),
    business_date DATE NOT NULL,
    raised_at TIMESTAMPTZ NOT NULL,
    raised_by_staff_id BIGINT NOT NULL REFERENCES staff(id),
    claimed_by_staff_id BIGINT REFERENCES staff(id),
    claimed_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    closed_by_staff_id BIGINT REFERENCES staff(id),
    outcome VARCHAR(20) CHECK (outcome IN ('RESOLVED_ONLINE', 'NEEDS_PHONE', 'REFERRED_OUT', 'VISIT_ENDED', 'EXPIRED')),
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX help_request_open_visit_idx ON help_request(visit_id) WHERE closed_at IS NULL;
