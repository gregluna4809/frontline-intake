ALTER TABLE sim_task ADD COLUMN staff_id BIGINT REFERENCES staff(id);
ALTER TABLE sim_task ADD COLUMN help_request_id BIGINT REFERENCES help_request(id);
