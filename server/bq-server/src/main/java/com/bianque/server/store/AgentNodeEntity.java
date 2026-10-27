package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 平台 Agent 节点台账：部署在目标主机的 bq-agent，注册时落库，心跳刷新。
 */
@Entity
@Table(name = "agent_node")
public class AgentNodeEntity {

    @Id
    public String agentId;

    public String host;          // 上报的出口 IP
    public String hostname;
    public String os;
    public String javaVersion;
    public String agentVersion;
    public Instant firstSeen;
    public Instant lastHeartbeat;

    public AgentNodeEntity() {
    }

    public AgentNodeEntity(String agentId, String host, String hostname, String os,
                           String javaVersion, String agentVersion) {
        this.agentId = agentId;
        this.host = host;
        this.hostname = hostname;
        this.os = os;
        this.javaVersion = javaVersion;
        this.agentVersion = agentVersion;
        this.firstSeen = Instant.now();
        this.lastHeartbeat = this.firstSeen;
    }
}
