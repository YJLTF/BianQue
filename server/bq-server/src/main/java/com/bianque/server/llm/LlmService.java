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
 * LLM 接入服务（AI 问诊引擎）：OpenAI Chat Completions 兼容协议，支持同步与 SSE 流式两种调用。
 * - 配置单例落库（llm_config），首次读取时用 yml/env 默认值播种；api-key 只回传掩码；
 * - 思考强度：接入配置存默认档（OFF/MINIMAL/LOW/MEDIUM/HIGH）；每次对话可携带请求级覆盖
 *   （ZCode 式 none/low/medium/high，default=跟随配置）。非 OFF 按推理模型约束发送（省略
 *   temperature/top_p，max_tokens 用 max_completion_tokens 承载），OFF 发普通采样参数；
 * - 推理参数映射（reasoningParams，JSON）：按等级深度覆盖请求体参数，适配非 OpenAI 风格的
 *   推理开关（如 {"off":{"thinking":{"type":"disabled"}}}）；未映射的等级回退内置形态；
 * - 流式：stream=true 走上游 SSE，reasoning_content/content 增量实时回调；每次调用写审计，
 *   密钥不出平台——节点 Agent 保持零依赖瘦执行。
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

    /** 流式回调：思考/回答增量与最终汇总（实现方需自行容错，回调线程为 HTTP 客户端线程） */
    public interface StreamListener {

        void onReasoning(String delta);

        void onContent(String delta);

        void onDone(ChatOut out);

        void onError(String message);
    }

    /** 上游 SSE 流式增量累积器 */
    private static final class SseAcc {
        final StringBuilder reasoning = new StringBuilder();
        final StringBuilder content = new StringBuilder();
        int promptTokens;
        int completionTokens;
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

    /** 更新配置（null 字段保留原值）；apiKey 为空表示保留原值；reasoningParams 空串表示清空回内置映射 */
    public LlmConfigEntity update(String baseUrl, String apiKey, String model, Double temperature, Double topP,
                                  Integer maxTokens, String reasoningEffort, Integer timeoutSeconds,
                                  String systemPrompt, String reasoningParams) {
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
        e.reasoningParams = normalizedMapping(reasoningParams);
        e.updatedAt = Instant.now();
        return jpa.save(e);
    }

    /** 校验推理参数映射：null 保留原值 / 空串清空 / 非空必须是 JSON 对象 */
    private String normalizedMapping(String reasoningParams) {
        if (reasoningParams == null) {
            return null; // 调用方未触及该字段
        }
        String rp = reasoningParams.trim();
        if (rp.isEmpty()) {
            return null;
        }
        try {
            JsonNode tree = json.readTree(rp);
            if (tree == null || !tree.isObject() || tree.isEmpty()) {
                return null; // {} 等价于清空
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "推理参数映射需为合法 JSON 对象（键 off/minimal/low/medium/high）：" + ex.getMessage());
        }
        return rp;
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
        m.put("reasoningParams", nullToEmpty(e.reasoningParams));
        m.put("updatedAt", e.updatedAt);
        m.put("configured", isConfigured(e));
        return m;
    }

    /** 对话（同步，跟随接入配置的思考强度） */
    public ChatOut chat(String operator, String instanceId, String question,
                        List<ChatTurn> history, String context) {
        return chat(operator, instanceId, question, history, context, null);
    }

    /** 对话（同步）：reasoningLevel 为请求级思考强度覆盖（default/null=跟随配置） */
    public ChatOut chat(String operator, String instanceId, String question,
                        List<ChatTurn> history, String context, String reasoningLevel) {
        LlmConfigEntity c = config();
        if (!isConfigured(c)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "LLM 未配置：请先在「AI 问诊」页填写 OpenAI 兼容端点、API Key 与模型名");
        }
        String q = cap(question, 8000);
        // 知识库检索：问题 + 诊断上下文共同作为检索源；无命中则不注入（寒暄/闲聊零干扰）
        List<DocsService.DocHit> kb = docs.searchKnowledge(q + "\n" + (context == null ? "" : context), MAX_KB_DOCS);
        List<Map<String, Object>> messages = prepareMessages(c, q, kb, history, context);
        String eff = resolveEffort(c.reasoningEffort, reasoningLevel);
        Map<String, Object> body = buildRequestBody(c, messages, eff, mapping(c));

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
            String reasoning = textOf(msg, "reasoning_content");
            if (reasoning.isEmpty()) {
                reasoning = textOf(msg, "reasoning"); // OpenRouter 等网关的思考字段名
            }
            ChatOut out = new ChatOut(
                    msg.path("content").asText(""),
                    reasoning,
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

    /**
     * 对话（SSE 流式）：上游 stream=true，思考（reasoning_content）与回答增量实时回调；
     * 结束回调 onDone（全量汇总 + refs），失败回调 onError。请求级思考强度同同步链路。
     */
    public void chatStreaming(String operator, String instanceId, String question,
                              List<ChatTurn> history, String context, String reasoningLevel,
                              StreamListener listener) {
        LlmConfigEntity c = config();
        if (!isConfigured(c)) {
            listener.onError("LLM 未配置：请先在「AI 问诊」页填写 OpenAI 兼容端点、API Key 与模型名");
            return;
        }
        String q = cap(question, 8000);
        List<DocsService.DocHit> kb = docs.searchKnowledge(q + "\n" + (context == null ? "" : context), MAX_KB_DOCS);
        List<DocRef> refs = kb.stream().map(h -> new DocRef(h.slug(), h.title(), h.path())).toList();
        List<Map<String, Object>> messages = prepareMessages(c, q, kb, history, context);
        String eff = resolveEffort(c.reasoningEffort, reasoningLevel);
        Map<String, Object> body = buildRequestBody(c, messages, eff, mapping(c));
        body.put("stream", true);
        if (!body.containsKey("stream_options")) {
            body.put("stream_options", Map.of("include_usage", true)); // OpenAI 风格：usage 随最后一帧回传
        }

        long begin = System.currentTimeMillis();
        try {
            String url = trim(c.baseUrl).replaceAll("/+$", "") + "/chat/completions";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(c.timeoutSeconds == null ? 60 : c.timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + c.apiKey)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            SseAcc acc = new SseAcc();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofLines()).whenComplete((resp, err) -> {
                if (err != null) {
                    fail(operator, instanceId, q, begin, err.getClass().getSimpleName());
                    listener.onError("LLM 调用失败：" + err.getClass().getSimpleName()
                            + (err.getMessage() == null ? "" : " " + err.getMessage()));
                    return;
                }
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    StringBuilder errBody = new StringBuilder();
                    resp.body().forEach(l -> errBody.append(l).append('\n'));
                    fail(operator, instanceId, q, begin, "HTTP " + resp.statusCode());
                    listener.onError("LLM 调用失败 HTTP " + resp.statusCode() + ": " + cap(errBody.toString(), 300));
                    return;
                }
                try {
                    resp.body().forEach(line -> feed(acc, line, listener));
                    ChatOut out = new ChatOut(acc.content.toString(), acc.reasoning.toString(),
                            acc.promptTokens, acc.completionTokens,
                            System.currentTimeMillis() - begin, c.model, refs);
                    audit(operator, instanceId, q, out.costMs(), "ok");
                    listener.onDone(out);
                } catch (Exception e) {
                    fail(operator, instanceId, q, begin, e.getClass().getSimpleName());
                    listener.onError("LLM 流式解析失败：" + e.getMessage());
                }
            });
        } catch (Exception e) {
            fail(operator, instanceId, q, begin, e.getClass().getSimpleName());
            listener.onError("LLM 调用失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** 上游 SSE 单行解析：data: 帧 → delta.reasoning_content / delta.content 增量回调；usage 随帧捕获 */
    private void feed(SseAcc acc, String rawLine, StreamListener listener) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.startsWith(":") || !line.startsWith("data:")) {
            return; // 注释行 / 心跳 / 非数据帧
        }
        String payload = line.substring(5).trim();
        if ("[DONE]".equals(payload)) {
            return;
        }
        try {
            JsonNode root = json.readTree(payload);
            JsonNode usage = root.path("usage");
            if (usage.isObject()) {
                acc.promptTokens = usage.path("prompt_tokens").asInt(acc.promptTokens);
                acc.completionTokens = usage.path("completion_tokens").asInt(acc.completionTokens);
            }
            JsonNode delta = root.path("choices").path(0).path("delta");
            String r = textOf(delta, "reasoning_content");
            if (r.isEmpty()) {
                r = textOf(delta, "reasoning");
            }
            if (!r.isEmpty()) {
                acc.reasoning.append(r);
                listener.onReasoning(r);
            }
            String d = textOf(delta, "content");
            if (!d.isEmpty()) {
                acc.content.append(d);
                listener.onContent(d);
            }
        } catch (Exception ignore) {
            // 单帧解析失败不中断整条流
        }
    }

    /** 系统提示词（+知识库命中+诊断上下文）→ 历史（截尾）→ 本轮问题 */
    private List<Map<String, Object>> prepareMessages(LlmConfigEntity c, String q, List<DocsService.DocHit> kb,
                                                      List<ChatTurn> history, String context) {
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
        return messages;
    }

    /** 请求级思考强度：default/空 → 接入配置默认；否则限 off/minimal/low/medium/high（非法 400） */
    static String resolveEffort(String configured, String requested) {
        if (requested == null || requested.isBlank() || "default".equalsIgnoreCase(requested.trim())) {
            return configured == null ? LlmConfigEntity.Effort.OFF : configured;
        }
        String r = requested.trim().toUpperCase();
        for (String v : List.of(LlmConfigEntity.Effort.OFF, LlmConfigEntity.Effort.MINIMAL,
                LlmConfigEntity.Effort.LOW, LlmConfigEntity.Effort.MEDIUM, LlmConfigEntity.Effort.HIGH)) {
            if (v.equalsIgnoreCase(r)) {
                return v;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的思考强度：" + requested);
    }

    /** 库内推理参数映射 → Map（保存时已校验；运行期坏数据按内置处理） */
    private Map<String, Object> mapping(LlmConfigEntity c) {
        if (c.reasoningParams == null || c.reasoningParams.isBlank()) {
            return null;
        }
        try {
            return json.readValue(c.reasoningParams,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 请求体组装：model/messages + 思考参数形态。
     * 自定义映射命中该等级 → 深度覆盖（仅兜底 max_tokens 缺省）；未命中/未配置 → 内置：
     * OFF=普通采样参数（temperature/top_p/max_tokens），其余=reasoning_effort + max_completion_tokens。
     */
    static Map<String, Object> buildRequestBody(LlmConfigEntity c, List<Map<String, Object>> messages,
                                                String effort, Map<String, Object> customMapping) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", c.model);
        body.put("messages", messages);
        Map<String, Object> frag = customMapping == null ? null : asMap(customMapping.get(effort.toLowerCase()));
        if (frag != null && !frag.isEmpty()) {
            body.putAll(frag);
            if (!frag.containsKey("max_tokens") && !frag.containsKey("max_completion_tokens")) {
                body.put("max_tokens", c.maxTokens);
            }
            return body;
        }
        if (LlmConfigEntity.Effort.OFF.equals(effort)) {
            body.put("temperature", c.temperature);
            body.put("top_p", c.topP);
            body.put("max_tokens", c.maxTokens);
        } else {
            body.put("reasoning_effort", effort.toLowerCase());
            body.put("max_completion_tokens", c.maxTokens);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isTextual() ? n.asText() : "";
    }

    private void fail(String operator, String instanceId, String q, long begin, String reason) {
        audit(operator, instanceId, q, System.currentTimeMillis() - begin, "fail: " + reason);
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
