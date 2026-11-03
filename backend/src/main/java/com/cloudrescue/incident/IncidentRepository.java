package com.cloudrescue.incident;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {
    boolean existsByServiceNameAndStateNot(String serviceName, IncidentState state);

    /** The unresolved incident (if any) that currently blocks new incidents for this service. */
    Optional<Incident> findFirstByServiceNameAndStateNotOrderByIdDesc(String serviceName, IncidentState state);

    List<Incident> findByStateIn(Collection<IncidentState> states);
    Page<Incident> findByState(IncidentState state, Pageable pageable);

    /** Unresolved incidents = every state except RECOVERED (used by the cloudrescue.active.incidents gauge). */
    long countByStateNot(IncidentState state);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Incident i where i.id = ?1")
    Optional<Incident> findByIdForUpdate(Long id);
}
