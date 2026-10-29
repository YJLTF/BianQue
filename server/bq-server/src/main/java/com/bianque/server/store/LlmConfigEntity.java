package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * LLM 接入配置（单例行 id=1）：OpenAI Chat Completions 兼容端点 + 采样参数 + 提示词。
 * 由 AI 问诊页在线编辑；api-key 落库但接口只回传掩码。
 */
@Entity
@Table(name = "llm_config")
public class LlmConfigEntity {

    /** 思考强度：OFF=不发送 reasoning_effort（普通对话模型），其余透传给推理模型 */
    public static final class Effort {
        public static final String OFF = "OFF";
        public static final String MINIMAL = "MINIMAL";
        public static final String LOW = "LOW";
        public static final String MEDIUM = "MEDIUM";
        public static final String HIGH = "HIGH";

        private Effort() {
        }
    }

    @Id
    public Long id = 1L;

    public String baseUrl;        // 例 https://api.openai.com/v1（不带 /chat/completions 尾巴）
    public String apiKey;
    public String model;
    public Double temperature;
    public Double topP;
    public Integer maxTokens;
    public String reasoningEffort = Effort.OFF;
    public Integer timeoutSeconds;
    @Lob
    public String systemPrompt;
    /**
     * 推理参数映射（JSON，按思考强度自定义请求体附加参数，深度合并）：
     * {"off":{"thinking":{"type":"disabled"}},"high":{"reasoning_effort":"high","top_p":0.95}}。
     * 键：off/minimal/low/medium/high；某等级未给出或整段留空 → 内置 OpenAI 风格（reasoning_effort + max_completion_tokens）。
     */
    @Lob
    public String reasoningParams;

    /** 文档向量化（RAG）：OpenAI 兼容 /embeddings 端点（不带 /embeddings 尾巴）；留空 = 关闭向量检索走关键词 */
    public String embedBaseUrl;
    public String embedApiKey;
    public String embedModel;
    public Integer embedDims;
    public Instant updatedAt;

    public LlmConfigEntity() {
    }

    public LlmConfigEntity(String baseUrl, String apiKey, String model, Double temperature, Double topP,
                           Integer maxTokens, String reasoningEffort, Integer timeoutSeconds, String systemPrompt) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.reasoningEffort = reasoningEffort == null ? Effort.OFF : reasoningEffort;
        this.timeoutSeconds = timeoutSeconds;
        this.systemPrompt = systemPrompt;
        this.updatedAt = Instant.now();
    }
}
