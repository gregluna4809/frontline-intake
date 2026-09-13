ALTER TABLE visit ADD COLUMN business_date DATE;
UPDATE visit SET business_date = (created_at AT TIME ZONE 'America/New_York')::date;
ALTER TABLE visit ALTER COLUMN business_date SET NOT NULL;

-- Ticket labels repeat on each business day; visit IDs remain globally unique.
ALTER TABLE visit DROP CONSTRAINT visit_ticket_key;
ALTER TABLE visit ADD CONSTRAINT visit_business_date_ticket_key UNIQUE (business_date, ticket);
CREATE INDEX visit_business_date_status_created_at_idx ON visit(business_date, status, created_at, id);
