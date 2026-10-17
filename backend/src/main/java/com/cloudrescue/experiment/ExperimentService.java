package com.cloudrescue.experiment;

import com.cloudrescue.common.Errors.BadRequestException;
import com.cloudrescue.policy.PolicyRegistry;
import com.cloudrescue.policy.RecoveryPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs repeatable experiments. For trial i every policy uses seed baseSeed + i, so each policy faces the
 * same fault durations (common random numbers) and differences come from the policy, not luck.
 */
@Service
public class ExperimentService {
    private final ExperimentRunRepository repo;
    private final PolicyRegistry policies;
    private final Clock clock;

    public ExperimentService(ExperimentRunRepository repo, PolicyRegistry policies, Clock clock) {
        this.repo = repo;
        this.policies = policies;
        this.clock = clock;
    }

    public List<SimulationEngine.Scenario> scenarios() {
        return SimulationEngine.defaultScenarios();
    }

    @Transactional
    public String run(List<String> scenarioNames, List<String> policyNames, int trials, long baseSeed, boolean idempotency) {
        List<SimulationEngine.Scenario> chosenScenarios = new ArrayList<>();
        for (SimulationEngine.Scenario s : SimulationEngine.defaultScenarios()) {
            if (scenarioNames == null || scenarioNames.isEmpty() || scenarioNames.contains(s.name())) chosenScenarios.add(s);
        }
        if (chosenScenarios.isEmpty()) throw new BadRequestException("No matching scenarios");
        List<RecoveryPolicy> chosenPolicies = new ArrayList<>();
        if (policyNames == null || policyNames.isEmpty()) chosenPolicies.addAll(policies.all());
        else policyNames.forEach(n -> chosenPolicies.add(policies.get(n)));

        String batch = UUID.randomUUID().toString();
        var now = clock.instant();
        List<ExperimentRun> rows = new ArrayList<>();
        for (var sc : chosenScenarios) {
            for (RecoveryPolicy p : chosenPolicies) {
                for (int i = 0; i < trials; i++) {
                    long seed = baseSeed + i;
                    var r = SimulationEngine.run(sc, p, seed, idempotency);
                    ExperimentRun e = new ExperimentRun();
                    e.batchId = batch;
                    e.scenario = sc.name();
                    e.policyName = p.name();
                    e.trialIndex = i;
                    e.seed = seed;
                    e.idempotencyEnabled = idempotency;
                    e.observationSec = sc.observationSec();
                    e.detectionDelaySec = r.detectionDelaySec();
                    e.recoveryTimeSec = r.recoveryTimeSec();
                    e.totalTimeSec = r.totalTimeSec();
                    e.availability = r.availability();
                    e.recovered = r.recovered();
                    e.escalated = r.escalated();
                    e.duplicateOps = r.duplicateOps();
                    e.cost = r.cost();
                    e.retries = r.retries();
                    e.restarts = r.restarts();
                    e.failovers = r.failovers();
                    e.createdAt = now;
                    rows.add(e);
                }
            }
        }
        repo.saveAll(rows);
        return batch;
    }
}
