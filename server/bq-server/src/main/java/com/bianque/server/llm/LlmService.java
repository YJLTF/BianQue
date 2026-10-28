package com.bianque.server.llm;

import com.bianque.server.repo.AuditTrail;
import com.bianque.server.service.DocsService;
import com.bianque.server.store.LlmConfigEntity;
import com.bianque.server.store.LlmConfigJpa;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * LLM 接入服务（AI 问诊引擎）：OpenAI Chat Completions 兼容协议。
 * - 配置单例落库（llm_config），首次读取时用 yml/env 默认值播种；api-key 只回传掩码；
 * - 思考强度 reasoning_effort：非 OFF 时按推理模型约束发送（省略 temperature/top_p，
 *   max_tokens 用 max_completion_tokens 承载）；OFF 时按普通对话模型发送采样参数；
 * - 每次调用写审计（问题摘要 + 耗时 + 结果），密钥不出平台——节点 Agent 保持零依赖瘦执行。
 */
@Service
public class LlmService {

    public record ChatTurn(String role, String content) {
    }

    /** 命中的知识库参考文档（前端可点击跳文档中心） */
    public record DocRef(String slug, String title, String path) {
    }

    public record ChatOut(String content, String reasoning, int promptTokens, int completionTokens,
                          long costMs, String model, List<DocRef> refs) {
    }

    private static final int MAX_HISTORY = 10;
    private static final int MAX_TEXT = 12_000;
    private static final int MAX_KB_DOCS = 4;
    private static final int MAX_KB_DOC_CHARS = 2_500;

    private final LlmConfigJpa jpa;
    private final DocsService docs;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String defBaseUrl;
    private final String defApiKey;
    private final String defModel;
    private final String defSystemPrompt;

    public LlmService(LlmConfigJpa jpa, DocsService docs, ObjectProvider<AuditTrail> audit, ObjectMapper json,
                      @Value("${bianque.llm.base-url:}") String defBaseUrl,
                      @Value("${bianque.llm.api-key:}") String defApiKey,
                      @Value("${bianque.llm.model:}") String defModel,
                      @Value("${bianque.llm.system-prompt:}") String defSystemPrompt) {
        this.jpa = jpa;
        this.docs = docs;
        this.audit = audit.getIfAvailable();
        this.json = json;
        this.defBaseUrl = defBaseUrl;
        this.defApiKey = defApiKey;
        this.defModel = defModel;
        this.defSystemPrompt = defSystemPrompt;
    }

    /** 读取配置（首次自动播种）；返回库内实体（含明文 key，仅服务端内部使用） */
    public synchronized LlmConfigEntity config() {
        LlmConfigEntity e = jpa.findById(1L).orElse(null);
        if (e == null) {
            e = new LlmConfigEntity(defBaseUrl, defApiKey, defModel, 0.7, 1.0, 2048,
                    LlmConfigEntity.Effort.OFF, 60, defSystemPrompt);
            e.id = 1L;
            e = jpa.save(e);
        }
        return e;
    }

    /** 更新配置（null 字段保留原值）；apiKey 为空表示保留原值 */
    public LlmConfigEntity update(String baseUrl, String apiKey, String model, Double temperature, Double topP,
                                  Integer maxTokens, String reasoningEffort, Integer timeoutSeconds,
                                  String systemPrompt) {
        LlmConfigEntity e = config();
        if (baseUrl != null) {
            e.baseUrl = baseUrl.trim();
        }
        if (apiKey != null && !apiKey.isBlank()) {
            e.apiKey = apiKey.trim();
        }
        if (model != null) {
            e.model = model.trim();
        }
        e.temperature = clamp(temperature, 0, 2, e.temperature);
        e.topP = clamp(topP, 0, 1, e.topP);
        e.maxTokens = maxTokens != null && maxTokens > 0 ? Math.min(maxTokens, 200_000) : e.maxTokens;
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            e.reasoningEffort = validEffort(reasoningEffort);
        }
        e.timeoutSeconds = timeoutSeconds != null && timeoutSeconds >= 3 ? Math.min(timeoutSeconds, 600) : e.timeoutSeconds;
        if (systemPrompt != null) {
            e.systemPrompt = systemPrompt;
        }
        e.updatedAt = Instant.now();
        return jpa.save(e);
    }

    /** 掩码后的配置（对外形态）：api-key 只给 hasKey + 掩码尾 */
    public Map<String, Object> maskedConfig() {
        LlmConfigEntity e = config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseUrl", nullToEmpty(e.baseUrl));
        m.put("hasApiKey", e.apiKey != null && !e.apiKey.isBlank());
        m.put("apiKeyMasked", mask(e.apiKey));
        m.put("model", nullToEmpty(e.model));
        m.put("temperature", e.temperature);
        m.put("topP", e.topP);
        m.put("maxTokens", e.maxTokens);
        m.put("reasoningEffort", e.reasoningEffort);
        m.put("timeoutSeconds", e.timeoutSeconds);
        m.put("systemPrompt", nullToEmpty(e.systemPrompt));
        m.put("updatedAt", e.updatedAt);
        m.put("configured", isConfigured(e));
        return m;
    }

    /** 对话：系统提示词（+知识库命中+诊断上下文）→ 历史（截尾）→ 本轮问题 */
    public ChatOut chat(String operator, String instanceId, String question,
                        List<ChatTurn> history, String context) {
        LlmConfigEntity c = config();
        if (!isConfigured(c)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "LLM 未配置：请先在「AI 问诊」页填写 OpenAI 兼容端点、API Key 与模型名");
        }
        String q = cap(question, 8000);
        // 知识库检索：问题 + 诊断上下文共同作为检索源；无命中则不注入（寒暄/闲聊零干扰）
        List<DocsService.DocHit> kb = docs.searchKnowledge(q + "\n" + (context == null ? "" : context), MAX_KB_DOCS);
        List<Map<String, Object>> messages = new ArrayList<>();
        String system = c.systemPrompt == null || c.systemPrompt.isBlank()
                ? "你是线上 JVM 诊断助手。" : c.systemPrompt;
        if (!kb.isEmpty()) {
            system = system + "\n\n" + knowledgeBlock(kb);
        }
        if (context != null && !context.isBlank()) {
            system = system + "\n\n【诊断上下文（平台采集，供分析参考）】\n" + cap(context, MAX_TEXT);
        }
        messages.add(Map.of("role", "system", "content", system));
        if (history != null) {
            for (ChatTurn t : history) {
                if (t == null || t.content() == null || t.content().isBlank()) {
                    continue;
                }
                String role = "assistant".equals(t.role()) ? "assistant" : "user";
                messages.add(Map.of("role", role, "content", cap(t.content(), 6000)));
            }
        }
        messages.add(Map.of("role", "user", "content", q));

        boolean reasoning = c.reasoningEffort != null && !LlmConfigEntity.Effort.OFF.equals(c.reasoningEffort);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", c.model);
        body.put("messages", messages);
        if (reasoning) {
            body.put("reasoning_effort", c.reasoningEffort.toLowerCase());
            body.put("max_completion_tokens", c.maxTokens);
        } else {
            body.put("temperature", c.temperature);
            body.put("top_p", c.topP);
            body.put("max_tokens", c.maxTokens);
        }

        long begin = System.currentTimeMillis();
        try {
            String url = trim(c.baseUrl).replaceAll("/+$", "") + "/chat/completions";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(c.timeoutSeconds == null ? 60 : c.timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + c.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - begin;
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                audit(operator, instanceId, q, cost, "fail: HTTP " + resp.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 调用失败 HTTP "
                        + resp.statusCode() + ": " + cap(resp.body(), 300));
            }
            JsonNode root = json.readTree(resp.body());
            JsonNode msg = root.path("choices").path(0).path("message");
            ChatOut out = new ChatOut(
                    msg.path("content").asText(""),
                    msg.hasNonNull("reasoning_content") ? msg.path("reasoning_content").asText("") : "",
                    root.path("usage").path("prompt_tokens").asInt(0),
                    root.path("usage").path("completion_tokens").asInt(0),
                    cost, c.model,
                    kb.stream().map(h -> new DocRef(h.slug(), h.title(), h.path())).toList());
            audit(operator, instanceId, q, cost, "ok");
            return out;
        } catch (java.io.IOException | InterruptedException e) {
            long cost = System.currentTimeMillis() - begin;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            audit(operator, instanceId, q, cost, "fail: " + e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "LLM 调用失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** 知识库片段：每页标题 + 正文摘录（单页截断），整体再按 MAX_TEXT 兜底 */
    private static String knowledgeBlock(List<DocsService.DocHit> kb) {
        StringBuilder sb = new StringBuilder("【Arthas 知识库参考（来自平台文档中心，讲解命令用法时优先遵循以下内容）】");
        for (DocsService.DocHit h : kb) {
            sb.append("\n\n◆ ").append(h.title()).append("\n")
              .append(cap(h.snippet(), MAX_KB_DOC_CHARS));
        }
        return cap(sb.toString(), MAX_TEXT);
    }

    private void audit(String operator, String instanceId, String question, long costMs, String result) {
        if (audit == null) {
            return;
        }
        try {
            audit.append("AI 问诊", operator, instanceId == null || instanceId.isBlank() ? "-" : instanceId,
                    "chat: " + cap(question.replace('\n', ' '), 60), costMs, result);
        } catch (Exception ignore) {
            // 审计失败不阻断问答
        }
    }

    private static boolean isConfigured(LlmConfigEntity e) {
        return e.baseUrl != null && !e.baseUrl.isBlank()
                && e.apiKey != null && !e.apiKey.isBlank()
                && e.model != null && !e.model.isBlank();
    }

    private static String validEffort(String s) {
        if (s == null || s.isBlank()) {
            return LlmConfigEntity.Effort.OFF;
        }
        for (String v : List.of(LlmConfigEntity.Effort.OFF, LlmConfigEntity.Effort.MINIMAL,
                LlmConfigEntity.Effort.LOW, LlmConfigEntity.Effort.MEDIUM, LlmConfigEntity.Effort.HIGH)) {
            if (v.equalsIgnoreCase(s.trim())) {
                return v;
            }
        }
        return LlmConfigEntity.Effort.OFF;
    }

    private static Double clamp(Double v, double min, double max, Double fallback) {
        if (v == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, v));
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return "***" + tail;
    }
}
