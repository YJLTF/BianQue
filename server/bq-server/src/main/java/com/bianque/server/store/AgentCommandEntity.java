package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 平台 Agent 命令单：平台入队 PENDING → 节点心跳领走 RUNNING → 回传 DONE/FAILED。
 * 一行即一次诊断执行，同时是节点离线时的命令状态真相源。
 */
@Entity
@Table(name = "agent_command", indexes = {
        @Index(name = "idx_agent_cmd_status", columnList = "agentId,status"),
        @Index(name = "idx_agent_cmd_created", columnList = "createdAt")
})
public class AgentCommandEntity {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String agentId;
    public String instanceId;
    public String command;
    public String status;

    @Lob
    public String output;
    /** agent-json：节点回传 arthas agent 原始 JSON，由平台渲染；text：控制台文本直出 */
    public String outputKind;
    public String error;
    public long costMs;

    public Instant createdAt;
    public Instant finishedAt;

    public AgentCommandEntity() {
    }

    public AgentCommandEntity(String agentId, String instanceId, String command) {
        this.agentId = agentId;
        this.instanceId = instanceId;
        this.command = command;
        this.status = PENDING;
        this.createdAt = Instant.now();
    }
}
