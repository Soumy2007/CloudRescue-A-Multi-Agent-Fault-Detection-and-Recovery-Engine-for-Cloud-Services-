-- Phase 2: persist what each remediation action is expected to achieve, and what verification concluded.
ALTER TABLE remediation_actions
    ADD COLUMN target_node_id          BIGINT REFERENCES service_nodes (id),
    ADD COLUMN expected_active_node_id BIGINT REFERENCES service_nodes (id),
    ADD COLUMN outcome                 VARCHAR(24),
    ADD COLUMN version                 BIGINT NOT NULL DEFAULT 0;
