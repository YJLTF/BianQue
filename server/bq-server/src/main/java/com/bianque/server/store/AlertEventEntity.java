package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 告警事件落库：外部告警系统经 /api/alerts/ingest 推入（自有告警系统的接入面）。
 */
@Entity
@Table(name = "alert_event")
public class AlertEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String alertId;
    public String appId;
    public String instanceId;
    public String message;
    public String severity;
    public String source;   // 来源系统标识（接入方自定义）
    public String status;   // 待取证 / 已取证
    public Instant createdAt;

    public AlertEventEntity() {
    }

    public AlertEventEntity(String alertId, String appId, String instanceId, String message,
                            String severity, String source, String status, Instant createdAt) {
        this.alertId = alertId;
        this.appId = appId;
        this.instanceId = instanceId;
        this.message = message;
        this.severity = severity;
        this.source = source;
        this.status = status;
        this.createdAt = createdAt;
    }
}
