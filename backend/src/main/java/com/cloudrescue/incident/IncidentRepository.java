package com.cloudrescue.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, Long> {
    boolean existsByServiceNameAndStateNot(String serviceName, IncidentState state);
    List<Incident> findByStateIn(Collection<IncidentState> states);
    Page<Incident> findByState(IncidentState state, Pageable pageable);
}
