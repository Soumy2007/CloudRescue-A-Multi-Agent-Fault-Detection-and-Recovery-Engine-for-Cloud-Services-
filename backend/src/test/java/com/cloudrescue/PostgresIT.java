package com.cloudrescue;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** One PostgreSQL container shared by all integration tests (singleton pattern, safe with Spring's context cache). */
abstract class PostgresIT {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        PG.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("DELETE FROM policy_runs");
        jdbc.execute("DELETE FROM remediation_actions");
        jdbc.execute("DELETE FROM incident_state_transitions");
        jdbc.execute("DELETE FROM incidents");
        jdbc.execute("DELETE FROM fault_injections");
        jdbc.execute("DELETE FROM idempotency_records");
        jdbc.execute("UPDATE service_nodes SET active = (role = 'PRIMARY')");
    }
}
