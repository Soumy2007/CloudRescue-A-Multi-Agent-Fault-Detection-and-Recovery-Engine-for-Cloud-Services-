package com.cloudrescue.simulator;

import jakarta.persistence.*;

/** One simulated service instance in a logical region. Entities use public fields for brevity. */
@Entity
@Table(name = "service_nodes")
public class ServiceNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String serviceName;
    public String region;
    @Enumerated(EnumType.STRING)
    public NodeRole role;
    /** True for the node currently receiving traffic for its logical service. */
    public boolean active;
}
