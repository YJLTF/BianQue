package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 本机纳管实例落库：后端重启后无需重新 attach（进程存活即可继续诊断）。
 * 仅存 localAgent 实例；模拟靶场实例仍由代码种子。
 */
@Entity
@Table(name = "managed_instance")
public class ManagedInstanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String instanceId; // local-<pid>
    public String appId;
    public String host;
    public String agentVersion;
    public boolean online;
    public int baseHealth;
    public String mainClass;
    public Instant registeredAt;

    public ManagedInstanceEntity() {
    }

    public ManagedInstanceEntity(String instanceId, String appId, String host, String agentVersion,
                                 boolean online, int baseHealth, String mainClass, Instant registeredAt) {
        this.instanceId = instanceId;
        this.appId = appId;
        this.host = host;
        this.agentVersion = agentVersion;
        this.online = online;
        this.baseHealth = baseHealth;
        this.mainClass = mainClass;
        this.registeredAt = registeredAt;
    }
}
