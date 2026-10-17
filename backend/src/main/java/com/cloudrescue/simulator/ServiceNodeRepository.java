package com.cloudrescue.simulator;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceNodeRepository extends JpaRepository<ServiceNode, Long> {
    List<ServiceNode> findByServiceName(String serviceName);
    Optional<ServiceNode> findByServiceNameAndActiveTrue(String serviceName);
    Optional<ServiceNode> findFirstByServiceNameAndActiveFalse(String serviceName);
}
