CREATE TABLE customer (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    language VARCHAR(5) NOT NULL CHECK (language IN ('EN', 'ES', 'OTHER')),
    veteran BOOLEAN NOT NULL
);

CREATE TABLE resource (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type VARCHAR(10) NOT NULL CHECK (type IN ('PHONE', 'COMPUTER', 'STATION')),
    label VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(10) NOT NULL CHECK (status IN ('FREE', 'OCCUPIED'))
);

CREATE SEQUENCE visit_ticket_sequence START WITH 1;

CREATE TABLE visit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket VARCHAR(32) NOT NULL UNIQUE,
    visit_type VARCHAR(10) NOT NULL CHECK (visit_type = 'WALK_IN'),
    reason VARCHAR(30) NOT NULL CHECK (reason IN (
        'PHONE_ACCESS', 'COMPUTER_ACCESS', 'DOCUMENT_SERVICES',
        'BENEFITS_ASSISTANCE', 'JOB_SEARCH', 'OTHER'
    )),
    status VARCHAR(20) NOT NULL CHECK (status IN (
        'CHECKED_IN', 'IDENTIFIED', 'AT_RESOURCE', 'WAITING_RESOURCE', 'CLOSED', 'LEFT'
    )),
    customer_id BIGINT REFERENCES customer(id),
    resource_id BIGINT REFERENCES resource(id),
    created_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX visit_status_created_at_idx ON visit(status, created_at, id);
CREATE UNIQUE INDEX visit_active_resource_idx ON visit(resource_id)
    WHERE resource_id IS NOT NULL AND status = 'AT_RESOURCE';

CREATE TABLE visit_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit(id),
    from_status VARCHAR(20) CHECK (from_status IN (
        'CHECKED_IN', 'IDENTIFIED', 'AT_RESOURCE', 'WAITING_RESOURCE', 'CLOSED', 'LEFT'
    )),
    to_status VARCHAR(20) NOT NULL CHECK (to_status IN (
        'CHECKED_IN', 'IDENTIFIED', 'AT_RESOURCE', 'WAITING_RESOURCE', 'CLOSED', 'LEFT'
    )),
    actor VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX visit_event_visit_id_idx ON visit_event(visit_id, id);
