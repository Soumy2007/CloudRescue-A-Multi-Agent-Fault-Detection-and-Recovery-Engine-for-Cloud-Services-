CREATE TABLE service_nodes (
    id           BIGSERIAL PRIMARY KEY,
    service_name VARCHAR(64) NOT NULL,
    region       VARCHAR(8)  NOT NULL CHECK (region IN ('A', 'B')),
    role         VARCHAR(16) NOT NULL CHECK (role IN ('PRIMARY', 'BACKUP')),
    active       BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_service_region UNIQUE (service_name, region)
);

CREATE TABLE fault_injections (
    id               BIGSERIAL PRIMARY KEY,
    fault_type       VARCHAR(32) NOT NULL,
    target_node_id   BIGINT REFERENCES service_nodes (id),
    target_region    VARCHAR(8),
    severity         DOUBLE PRECISION NOT NULL DEFAULT 0,
    started_at       TIMESTAMPTZ NOT NULL,
    scheduled_end_at TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,
    end_reason       VARCHAR(32)
);
CREATE INDEX idx_fault_active ON fault_injections (ended_at) WHERE ended_at IS NULL;

CREATE TABLE incidents (
    id                 BIGSERIAL PRIMARY KEY,
    service_name       VARCHAR(64) NOT NULL,
    node_id            BIGINT      NOT NULL REFERENCES service_nodes (id),
    failure_type       VARCHAR(32) NOT NULL,
    state              VARCHAR(16) NOT NULL,
    policy_name        VARCHAR(32) NOT NULL,
    fault_injection_id BIGINT REFERENCES fault_injections (id),
    detected_at        TIMESTAMPTZ NOT NULL,
    recovered_at       TIMESTAMPTZ,
    closed_at          TIMESTAMPTZ,
    epoch              INT         NOT NULL DEFAULT 0,
    forced_action      VARCHAR(16),
    summary            VARCHAR(500),
    version            BIGINT      NOT NULL DEFAULT 0
);
-- Duplicate-incident prevention enforced by the database, not only by application code.
CREATE UNIQUE INDEX uq_one_unresolved_incident_per_service
    ON incidents (service_name) WHERE state <> 'RECOVERED';
CREATE INDEX idx_incident_state ON incidents (state);

CREATE TABLE incident_state_transitions (
    id          BIGSERIAL PRIMARY KEY,
    incident_id BIGINT      NOT NULL REFERENCES incidents (id),
    from_state  VARCHAR(16),
    to_state    VARCHAR(16) NOT NULL,
    reason      VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_transition_incident ON incident_state_transitions (incident_id, occurred_at);

CREATE TABLE remediation_actions (
    id              BIGSERIAL PRIMARY KEY,
    incident_id     BIGINT      NOT NULL REFERENCES incidents (id),
    seq_no          INT         NOT NULL,
    epoch           INT         NOT NULL DEFAULT 0,
    action_type     VARCHAR(16) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    scheduled_at    TIMESTAMPTZ NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    attempts        INT         NOT NULL DEFAULT 0,
    result_message  VARCHAR(500),
    verified_healthy BOOLEAN,
    CONSTRAINT uq_incident_seq UNIQUE (incident_id, seq_no)
);

CREATE TABLE idempotency_records (
    idem_key     VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    result       VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE policy_runs (
    id                    BIGSERIAL PRIMARY KEY,
    incident_id           BIGINT      NOT NULL REFERENCES incidents (id),
    policy_name           VARCHAR(32) NOT NULL,
    failure_type          VARCHAR(32) NOT NULL,
    decision_action       VARCHAR(16) NOT NULL,
    delay_ms              BIGINT      NOT NULL,
    reason                VARCHAR(500) NOT NULL,
    rejected_alternatives VARCHAR(1000),
    decided_at            TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_policy_run_incident ON policy_runs (incident_id);

CREATE TABLE experiment_runs (
    id                  BIGSERIAL PRIMARY KEY,
    batch_id            VARCHAR(36) NOT NULL,
    scenario            VARCHAR(48) NOT NULL,
    policy_name         VARCHAR(32) NOT NULL,
    trial_index         INT         NOT NULL,
    seed                BIGINT      NOT NULL,
    idempotency_enabled BOOLEAN     NOT NULL,
    observation_sec     INT         NOT NULL,
    detection_delay_sec DOUBLE PRECISION,
    recovery_time_sec   DOUBLE PRECISION,
    total_time_sec      DOUBLE PRECISION,
    availability        DOUBLE PRECISION NOT NULL,
    recovered           BOOLEAN     NOT NULL,
    escalated           BOOLEAN     NOT NULL,
    duplicate_ops       INT         NOT NULL,
    cost                DOUBLE PRECISION NOT NULL,
    retries             INT         NOT NULL,
    restarts            INT         NOT NULL,
    failovers           INT         NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_experiment_batch ON experiment_runs (batch_id);
