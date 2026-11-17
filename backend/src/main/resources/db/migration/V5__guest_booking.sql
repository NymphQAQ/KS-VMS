ALTER TABLE visit
    ADD COLUMN guest_key CHAR(64),
    ADD COLUMN contact_phone VARCHAR(80),
    ADD INDEX visit_guest_key_id (guest_key, id);
