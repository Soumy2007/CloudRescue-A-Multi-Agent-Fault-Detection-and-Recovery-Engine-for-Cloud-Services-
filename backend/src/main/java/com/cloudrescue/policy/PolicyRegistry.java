package com.cloudrescue.policy;

import com.cloudrescue.common.Errors.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PolicyRegistry {
    private final Map<String, RecoveryPolicy> policies = new LinkedHashMap<>();
    private volatile String active;

    public PolicyRegistry(@Value("${cloudrescue.recovery.default-policy:COST_AWARE}") String defaultPolicy) {
        for (RecoveryPolicy p : List.of(new FixedRetryPolicy(), new ExponentialBackoffPolicy(), new CostAwarePolicy())) {
            policies.put(p.name(), p);
        }
        this.active = policies.containsKey(defaultPolicy) ? defaultPolicy : "COST_AWARE";
    }

    public RecoveryPolicy get(String name) {
        RecoveryPolicy p = policies.get(name);
        if (p == null) throw new BadRequestException("Unknown policy '" + name + "'. Known: " + policies.keySet());
        return p;
    }

    public Collection<RecoveryPolicy> all() { return policies.values(); }

    public RecoveryPolicy active() { return policies.get(active); }

    public void setActive(String name) { this.active = get(name).name(); }
}
