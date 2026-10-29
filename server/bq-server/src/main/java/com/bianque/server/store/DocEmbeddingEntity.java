package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 文档向量索引：按页分块（slug + chunkIdx 唯一），向量以 JSON 数组落库（78 页规模内存检索足够）。
 * contentHash 变化才重嵌，避免启动重复消耗。
 */
@Entity
@Table(name = "doc_embedding")
public class DocEmbeddingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String slug;
    public int chunkIdx;
    public String title;

    @Lob
    public String content;

    @Lob
    public String vecJson;

    public int dims;
    public String model;
    public String contentHash;
    public Instant updatedAt;

    public DocEmbeddingEntity() {
    }

    public DocEmbeddingEntity(String slug, int chunkIdx, String title, String content,
                              String vecJson, int dims, String model, String contentHash) {
        this.slug = slug;
        this.chunkIdx = chunkIdx;
        this.title = title;
        this.content = content;
        this.vecJson = vecJson;
        this.dims = dims;
        this.model = model;
        this.contentHash = contentHash;
        this.updatedAt = Instant.now();
    }
}
