package com.cloudrescue.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentTransitionRepository extends JpaRepository<IncidentTransition, Long> {
    List<IncidentTransition> findByIncidentIdOrderByIdAsc(Long incidentId);
}
