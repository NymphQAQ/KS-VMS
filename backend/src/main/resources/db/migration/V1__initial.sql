CREATE TABLE site (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    lead_minutes INT NOT NULL DEFAULT 120,
    advance_days INT NOT NULL DEFAULT 30,
    wait_minutes INT NOT NULL DEFAULT 60,
    max_minutes INT NOT NULL DEFAULT 720,
    open_time TIME NOT NULL DEFAULT '00:00:00',
    close_time TIME NOT NULL DEFAULT '00:00:00',
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE gate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    site_id BIGINT NOT NULL REFERENCES site (id),
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE area (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    site_id BIGINT NOT NULL REFERENCES site (id),
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE department (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE staff (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    department_id BIGINT REFERENCES department (id),
    host_public BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE staff_grant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    staff_id BIGINT NOT NULL REFERENCES staff (id),
    role VARCHAR(32) NOT NULL,
    site_id BIGINT REFERENCES site (id),
    department_id BIGINT REFERENCES department (id),
    gate_id BIGINT REFERENCES gate (id),
    start_at DATETIME(6),
    end_at DATETIME(6),
    INDEX (staff_id, role, site_id)
);

CREATE TABLE registration_link (
    slug VARCHAR(100) PRIMARY KEY,
    site_id BIGINT NOT NULL REFERENCES site (id),
    gate_id BIGINT REFERENCES gate (id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE notice (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    site_id BIGINT NOT NULL REFERENCES site (id),
    kind VARCHAR(20) NOT NULL,
    text TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX (site_id, kind, id)
);

CREATE TABLE otp_challenge (
    phone_key CHAR(64) PRIMARY KEY,
    code_key CHAR(64) NOT NULL,
    sent_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE request_limit (
    scope VARCHAR(24) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    window_start BIGINT NOT NULL,
    hits INT NOT NULL DEFAULT 1,
    PRIMARY KEY (scope, key_hash, window_start)
);

CREATE TABLE identity_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_type VARCHAR(24) NOT NULL,
    document_key CHAR(64) NOT NULL UNIQUE,
    document_tail VARCHAR(4) NOT NULL
);

CREATE TABLE invitation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_key CHAR(64) NOT NULL UNIQUE,
    phone_key CHAR(64) NOT NULL,
    inviter_id BIGINT NOT NULL REFERENCES staff (id),
    host_id BIGINT NOT NULL REFERENCES staff (id),
    site_id BIGINT NOT NULL REFERENCES site (id),
    guest_name VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    type VARCHAR(24) NOT NULL,
    planned_in DATETIME(6) NOT NULL,
    planned_out DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    claimed_at DATETIME(6),
    cancelled_at DATETIME(6),
    visit_id BIGINT
);

CREATE TABLE visit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    previous_id BIGINT REFERENCES visit (id),
    version INT NOT NULL DEFAULT 1,
    site_id BIGINT NOT NULL REFERENCES site (id),
    submitted_gate_id BIGINT REFERENCES gate (id),
    host_id BIGINT NOT NULL REFERENCES staff (id),
    department_id BIGINT NOT NULL REFERENCES department (id),
    creator_id BIGINT REFERENCES staff (id),
    invitation_id BIGINT REFERENCES invitation (id),
    identity_id BIGINT REFERENCES identity_record (id),
    source VARCHAR(16) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    type VARCHAR(24) NOT NULL,
    state VARCHAR(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone_key CHAR(64),
    company VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    plate VARCHAR(20),
    reference_no VARCHAR(100),
    area_id BIGINT REFERENCES area (id),
    planned_in DATETIME(6) NOT NULL,
    planned_out DATETIME(6) NOT NULL,
    window_start DATETIME(6) NOT NULL,
    window_end DATETIME(6) NOT NULL,
    max_minutes INT NOT NULL,
    safety_notice_id BIGINT NOT NULL REFERENCES notice (id),
    privacy_notice_id BIGINT NOT NULL REFERENCES notice (id),
    training_until DATETIME(6),
    permit_until DATETIME(6),
    permit_no VARCHAR(100),
    rejection VARCHAR(300),
    submitted_at DATETIME(6) NOT NULL,
    INDEX (phone_key, id),
    INDEX (site_id, state, window_end),
    INDEX (site_id, planned_in)
);

CREATE TABLE approval_step (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    visit_id BIGINT NOT NULL REFERENCES visit (id),
    sequence_no INT NOT NULL,
    role VARCHAR(40) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    UNIQUE (visit_id, sequence_no)
);

CREATE TABLE approval_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    step_id BIGINT NOT NULL UNIQUE REFERENCES approval_step (id),
    staff_id BIGINT NOT NULL REFERENCES staff (id),
    decision VARCHAR(16) NOT NULL,
    comment VARCHAR(300),
    acted_at DATETIME(6) NOT NULL
);

CREATE TABLE gate_pass (
    token_key CHAR(64) PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES visit (id),
    direction VARCHAR(5) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    INDEX (visit_id, direction, active)
);

CREATE TABLE presence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    visit_id BIGINT NOT NULL UNIQUE REFERENCES visit (id),
    site_id BIGINT NOT NULL REFERENCES site (id),
    identity_id BIGINT NOT NULL REFERENCES identity_record (id),
    plate VARCHAR(20),
    active_identity BIGINT GENERATED ALWAYS AS (IF(out_at IS NULL, identity_id, NULL)) STORED,
    active_plate VARCHAR(20) GENERATED ALWAYS AS (IF(vehicle_out_at IS NULL, plate, NULL)) STORED,
    in_at DATETIME(6) NOT NULL,
    out_at DATETIME(6),
    vehicle_in_at DATETIME(6),
    vehicle_out_at DATETIME(6),
    due_at DATETIME(6) NOT NULL,
    UNIQUE (site_id, active_identity),
    UNIQUE (site_id, active_plate),
    INDEX (site_id, out_at, due_at),
    INDEX (site_id, in_at),
    INDEX (site_id, vehicle_in_at)
);

CREATE TABLE gate_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    visit_id BIGINT REFERENCES visit (id),
    gate_id BIGINT NOT NULL REFERENCES gate (id),
    staff_id BIGINT NOT NULL REFERENCES staff (id),
    action VARCHAR(24) NOT NULL,
    outcome VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX (visit_id, id)
);

CREATE TABLE gate_request (
    staff_id BIGINT NOT NULL REFERENCES staff (id),
    direction VARCHAR(5) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    payload_key CHAR(64) NOT NULL,
    result_json TEXT,
    PRIMARY KEY (staff_id, direction, request_key)
);

CREATE TABLE blacklist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    identity_id BIGINT NOT NULL REFERENCES identity_record (id),
    site_id BIGINT REFERENCES site (id),
    reason VARCHAR(300) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6),
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX (identity_id, site_id, disabled)
);

CREATE TABLE alert (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    site_id BIGINT NOT NULL REFERENCES site (id),
    visit_id BIGINT REFERENCES visit (id),
    kind VARCHAR(50) NOT NULL,
    event_key VARCHAR(110) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assignee_id BIGINT REFERENCES staff (id),
    resolution VARCHAR(300),
    created_at DATETIME(6) NOT NULL,
    last_at DATETIME(6) NOT NULL,
    UNIQUE (site_id, kind, event_key),
    INDEX (site_id, state, last_at)
);

CREATE TABLE correction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    visit_id BIGINT NOT NULL REFERENCES visit (id),
    gate_id BIGINT NOT NULL REFERENCES gate (id),
    requested_by BIGINT NOT NULL REFERENCES staff (id),
    reviewed_by BIGINT REFERENCES staff (id),
    identity_id BIGINT REFERENCES identity_record (id),
    action VARCHAR(24) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reviewed_at DATETIME(6)
);

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    staff_id BIGINT REFERENCES staff (id),
    site_id BIGINT REFERENCES site (id),
    action VARCHAR(50) NOT NULL,
    reference_id BIGINT,
    detail VARCHAR(500),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX (site_id, id)
);
