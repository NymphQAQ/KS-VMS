ALTER TABLE gate ADD FOREIGN KEY (site_id) REFERENCES site(id);
ALTER TABLE area ADD FOREIGN KEY (site_id) REFERENCES site(id);
ALTER TABLE staff ADD FOREIGN KEY (department_id) REFERENCES department(id);
ALTER TABLE staff_grant
    ADD FOREIGN KEY (staff_id) REFERENCES staff(id),
    ADD FOREIGN KEY (site_id) REFERENCES site(id),
    ADD FOREIGN KEY (department_id) REFERENCES department(id),
    ADD FOREIGN KEY (gate_id) REFERENCES gate(id);
ALTER TABLE registration_link
    ADD FOREIGN KEY (site_id) REFERENCES site(id),
    ADD FOREIGN KEY (gate_id) REFERENCES gate(id);
ALTER TABLE notice ADD FOREIGN KEY (site_id) REFERENCES site(id);
ALTER TABLE invitation
    ADD FOREIGN KEY (inviter_id) REFERENCES staff(id),
    ADD FOREIGN KEY (host_id) REFERENCES staff(id),
    ADD FOREIGN KEY (site_id) REFERENCES site(id),
    ADD FOREIGN KEY (visit_id) REFERENCES visit(id);
ALTER TABLE visit
    ADD FOREIGN KEY (previous_id) REFERENCES visit(id),
    ADD FOREIGN KEY (site_id) REFERENCES site(id),
    ADD FOREIGN KEY (submitted_gate_id) REFERENCES gate(id),
    ADD FOREIGN KEY (host_id) REFERENCES staff(id),
    ADD FOREIGN KEY (department_id) REFERENCES department(id),
    ADD FOREIGN KEY (creator_id) REFERENCES staff(id),
    ADD FOREIGN KEY (invitation_id) REFERENCES invitation(id),
    ADD FOREIGN KEY (identity_id) REFERENCES identity_record(id),
    ADD FOREIGN KEY (area_id) REFERENCES area(id),
    ADD FOREIGN KEY (safety_notice_id) REFERENCES notice(id),
    ADD FOREIGN KEY (privacy_notice_id) REFERENCES notice(id);
ALTER TABLE approval_step ADD FOREIGN KEY (visit_id) REFERENCES visit(id);
ALTER TABLE approval_action
    ADD FOREIGN KEY (step_id) REFERENCES approval_step(id),
    ADD FOREIGN KEY (staff_id) REFERENCES staff(id);
ALTER TABLE gate_pass ADD FOREIGN KEY (visit_id) REFERENCES visit(id);
ALTER TABLE presence
    ADD FOREIGN KEY (visit_id) REFERENCES visit(id),
    ADD FOREIGN KEY (site_id) REFERENCES site(id),
    ADD FOREIGN KEY (identity_id) REFERENCES identity_record(id);
ALTER TABLE gate_event
    ADD FOREIGN KEY (visit_id) REFERENCES visit(id),
    ADD FOREIGN KEY (gate_id) REFERENCES gate(id),
    ADD FOREIGN KEY (staff_id) REFERENCES staff(id);
ALTER TABLE gate_request ADD FOREIGN KEY (staff_id) REFERENCES staff(id);
ALTER TABLE blacklist
    ADD FOREIGN KEY (identity_id) REFERENCES identity_record(id),
    ADD FOREIGN KEY (site_id) REFERENCES site(id);
ALTER TABLE alert
    ADD FOREIGN KEY (site_id) REFERENCES site(id),
    ADD FOREIGN KEY (visit_id) REFERENCES visit(id),
    ADD FOREIGN KEY (assignee_id) REFERENCES staff(id);
ALTER TABLE correction
    ADD FOREIGN KEY (visit_id) REFERENCES visit(id),
    ADD FOREIGN KEY (gate_id) REFERENCES gate(id),
    ADD FOREIGN KEY (requested_by) REFERENCES staff(id),
    ADD FOREIGN KEY (reviewed_by) REFERENCES staff(id),
    ADD FOREIGN KEY (identity_id) REFERENCES identity_record(id);
ALTER TABLE audit_log
    ADD FOREIGN KEY (staff_id) REFERENCES staff(id),
    ADD FOREIGN KEY (site_id) REFERENCES site(id);
