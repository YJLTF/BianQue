package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 诊断工件：性能剖析产物（profiler html 火焰图等）经命令通道回传后落库，
 * 平台以同源 URL 提供（/api/diag/artifact/{id}，登录态门禁，SAMEORIGIN 可内嵌）。
 */
@Entity
@Table(name = "diag_artifact")
public class DiagArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String instanceId;
    /** 类型：flame-html（火焰图） */
    public String type;

    @Lob
    public String content;

    public int sizeBytes;
    public Instant createdAt;

    public DiagArtifactEntity() {
    }

    public DiagArtifactEntity(String instanceId, String type, String content) {
        this.instanceId = instanceId;
        this.type = type;
        this.content = content;
        this.sizeBytes = content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        this.createdAt = Instant.now();
    }
}
