ALTER TABLE staff ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE holiday (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    date DATE NOT NULL UNIQUE,
    label VARCHAR(100) NOT NULL
);

-- Preserve the former BusinessCalendar list exactly, including Saturday dates
-- and the Sunday July 4 plus its observed Monday in 2027.
INSERT INTO holiday (date, label) VALUES
    ('2026-01-01', 'New Year''s Day'),
    ('2026-01-19', 'Martin Luther King Jr. Day'),
    ('2026-02-16', 'Presidents Day'),
    ('2026-05-25', 'Memorial Day'),
    ('2026-06-19', 'Juneteenth'),
    ('2026-07-04', 'Independence Day'),
    ('2026-09-07', 'Labor Day'),
    ('2026-10-12', 'Columbus Day'),
    ('2026-11-11', 'Veterans Day'),
    ('2026-11-26', 'Thanksgiving'),
    ('2026-11-27', 'Day after Thanksgiving'),
    ('2026-12-25', 'Christmas Day'),
    ('2027-01-01', 'New Year''s Day'),
    ('2027-01-18', 'Martin Luther King Jr. Day'),
    ('2027-02-15', 'Presidents Day'),
    ('2027-05-31', 'Memorial Day'),
    ('2027-06-19', 'Juneteenth'),
    ('2027-07-04', 'Independence Day'),
    ('2027-07-05', 'Independence Day (observed)'),
    ('2027-09-06', 'Labor Day'),
    ('2027-10-11', 'Columbus Day'),
    ('2027-11-11', 'Veterans Day'),
    ('2027-11-25', 'Thanksgiving'),
    ('2027-11-26', 'Day after Thanksgiving'),
    ('2027-12-25', 'Christmas Day');
