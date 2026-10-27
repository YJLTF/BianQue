package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 审计落库实体：追加写 + 哈希链（prev_hash → hash），任何中途篡改都会断链可检。
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_seq", columnList = "seq", unique = true),
        @Index(name = "idx_audit_time", columnList = "time")
})
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public long seq;
    public Instant time;
    public String timeText;   // 参与哈希的规范化时间串（防 DB 精度截断导致断链）
    public String source;
    public String operator;
    public String instanceId;
    public String command;
    public long costMs;
    public String result;
    public String prevHash;
    public String hash;

    public AuditLogEntity() {
    }

    public AuditLogEntity(long seq, Instant time, String timeText, String source, String operator,
                          String instanceId, String command, long costMs, String result,
                          String prevHash, String hash) {
        this.seq = seq;
        this.time = time;
        this.timeText = timeText;
        this.source = source;
        this.operator = operator;
        this.instanceId = instanceId;
        this.command = command;
        this.costMs = costMs;
        this.result = result;
        this.prevHash = prevHash;
        this.hash = hash;
    }
}
