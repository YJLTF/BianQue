package com.bianque.server.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 平台核心业务模型（V0.1.0 内存态；S1 起落 MySQL）。
 */
public final class Types {

    private Types() {
    }

    /** 受管应用下的实例（agent 信息；localAgent=true 时 mainClass 为 as.sh --select 目标） */
    public record Instance(
            String instanceId,
            String appId,
            String host,
            String agentVersion,
            boolean online,
            boolean localAgent,
            int baseHealth,
            String mainClass) {
    }

    /** 应用聚合视图 */
    public record AppSummary(
            String appId,
            String name,
            long instanceCount,
            long onlineCount,
            int healthScore,
            String status,
            String lastEvent) {
    }

    /** 审计条目（追加写；谁·何时·何实例·何命令·何结果；S1 起含哈希链字段） */
    public record AuditEntry(
            long seq,
            Instant time,
            String source,
            String operator,
            String instanceId,
            String command,
            long costMs,
            String result,
            String prevHash,
            String hash) {
    }

    /** 告警事件（Webhook 接入后的标准化形态） */
    public record Alert(
            String alertId,
            Instant time,
            String appId,
            String instanceId,
            String message,
            String severity,
            String status) {
    }

    /** 急诊取证：单步骤 */
    public record RescueStep(int order, String name, String command, String status, String outputHead) {
    }

    /** 急诊取证：现场报告 */
    public record RescueReport(
            String alertId,
            String instanceId,
            List<RescueStep> steps,
            String conclusion,
            List<String> threadTop,
            Map<String, Integer> memory,
            long costMs,
            Instant generatedAt) {
    }

    /** 巡检行：实例健康分与扣分项 */
    public record CheckupRow(
            String instanceId,
            int score,
            List<String> deductions) {
    }

    /** 档案扫描：单实例命中 */
    public record AtlasHit(
            String instanceId,
            String targetClass,
            boolean classFound,
            String sourceJar,
            String classloader,
            String verdict) {
    }

    /** 档案扫描汇总 */
    public record AtlasReport(
            String targetClass,
            int scanned,
            int hits,
            int vulnerable,
            long costMs,
            List<AtlasHit> hitsDetail,
            Instant generatedAt) {
    }

    /** 诊断会话 */
    public record SessionInfo(
            String sessionId,
            String instanceId,
            String openedBy,
            Instant openedAt,
            Instant expiresAt,
            boolean active) {
    }

    /** 工作台总览 */
    public record Overview(
            long appCount,
            long instanceCount,
            long onlineCount,
            double avgHealth,
            long auditCount,
            String executionMode,
            List<AppSummary> apps) {
    }
}
