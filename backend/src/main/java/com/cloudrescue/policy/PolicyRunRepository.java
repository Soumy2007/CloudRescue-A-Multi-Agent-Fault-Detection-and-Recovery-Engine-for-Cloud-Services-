package com.cloudrescue.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRunRepository extends JpaRepository<PolicyRun, Long> {
    List<PolicyRun> findByIncidentIdOrderByIdAsc(Long incidentId);
}
