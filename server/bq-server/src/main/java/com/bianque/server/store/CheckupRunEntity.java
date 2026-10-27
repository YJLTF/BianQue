package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 巡检运行落库：一次 runAll 对应一行，明细以 JSON 存储（结构演进不锁表）。
 */
@Entity
@Table(name = "checkup_run")
public class CheckupRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public Instant runAt;
    public String operator;
    public int instanceCount;
    public double avgScore;

    @Lob
    public String payloadJson;

    public CheckupRunEntity() {
    }

    public CheckupRunEntity(Instant runAt, String operator, int instanceCount, double avgScore, String payloadJson) {
        this.runAt = runAt;
        this.operator = operator;
        this.instanceCount = instanceCount;
        this.avgScore = avgScore;
        this.payloadJson = payloadJson;
    }
}
