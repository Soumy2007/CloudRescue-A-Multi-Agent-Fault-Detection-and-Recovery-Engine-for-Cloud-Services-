package com.cloudrescue.remediation;

import com.cloudrescue.policy.ActionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RemediationActionRepository extends JpaRepository<RemediationAction, Long> {
    List<RemediationAction> findByIncidentIdOrderBySeqNoAsc(Long incidentId);
    Optional<RemediationAction> findFirstByIncidentIdAndStatusInOrderBySeqNoDesc(Long incidentId, Collection<ActionStatus> s);
    long countByIncidentId(Long incidentId);
    long countByIncidentIdAndEpochAndActionTypeAndStatusIn(Long incidentId, int epoch, ActionType type, Collection<ActionStatus> s);
}
