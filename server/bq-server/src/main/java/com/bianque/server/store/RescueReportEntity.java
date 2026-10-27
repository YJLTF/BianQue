package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 急诊取证报告落库：alertId 唯一，报告以 JSON 存储，重启不丢现场。
 */
@Entity
@Table(name = "rescue_report")
public class RescueReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String alertId;
    public String instanceId;
    public Instant generatedAt;
    public String operator;

    @Lob
    public String payloadJson;

    public RescueReportEntity() {
    }

    public RescueReportEntity(String alertId, String instanceId, Instant generatedAt,
                              String operator, String payloadJson) {
        this.alertId = alertId;
        this.instanceId = instanceId;
        this.generatedAt = generatedAt;
        this.operator = operator;
        this.payloadJson = payloadJson;
    }
}
