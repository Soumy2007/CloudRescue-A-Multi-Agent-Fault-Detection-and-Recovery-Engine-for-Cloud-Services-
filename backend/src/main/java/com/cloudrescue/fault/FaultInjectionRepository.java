package com.cloudrescue.fault;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FaultInjectionRepository extends JpaRepository<FaultInjection, Long> {
    List<FaultInjection> findByEndedAtIsNull();
    List<FaultInjection> findTop30ByOrderByIdDesc();
}
