package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * AI 会诊工具调用留痕：每步工具/参数/审批决策/结果摘要落库（审计哈希链另有全量记录）。
 */
@Entity
@Table(name = "ai_tool_run")
public class AiToolRunEntity {

    public static final String DECISION_AUTO = "auto";        // 只读工具直接执行
    public static final String DECISION_APPROVED = "approved"; // 增强命令用户批准
    public static final String DECISION_DENIED = "denied";     // 用户拒绝
    public static final String DECISION_TIMEOUT = "timeout";   // 审批超时默认拒绝
    public static final String DECISION_BLOCKED = "blocked";   // 高危黑名单（不可放行）

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String consultId;
    public int step;
    public String tool;
    public String argsJson;
    public String command;
    public String decision;
    public String status; // ok / fail / denied / blocked

    @Lob
    public String resultSnippet;
    public long costMs;
    public String operator;
    public Instant createdAt;

    public AiToolRunEntity() {
    }

    public AiToolRunEntity(String consultId, int step, String tool, String argsJson, String command,
                           String decision, String status, String resultSnippet, long costMs, String operator) {
        this.consultId = consultId;
        this.step = step;
        this.tool = tool;
        this.argsJson = argsJson;
        this.command = command;
        this.decision = decision;
        this.status = status;
        this.resultSnippet = resultSnippet;
        this.costMs = costMs;
        this.operator = operator;
        this.createdAt = Instant.now();
    }
}
