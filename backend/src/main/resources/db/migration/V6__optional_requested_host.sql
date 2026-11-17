ALTER TABLE visit
    MODIFY COLUMN host_id BIGINT NULL,
    MODIFY COLUMN department_id BIGINT NULL,
    ADD COLUMN requested_host VARCHAR(100);
