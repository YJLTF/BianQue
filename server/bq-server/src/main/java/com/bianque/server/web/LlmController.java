package com.bianque.server.web;

import com.bianque.server.auth.AuthContext;
import com.bianque.server.auth.AuthInterceptor;
import com.bianque.server.llm.LlmService;
import com.bianque.server.llm.LlmService.ChatOut;
import com.bianque.server.llm.LlmService.ChatTurn;
import com.bianque.server.llm.LlmService.StreamListener;
import com.bianque.server.store.LlmConfigEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * LLM 接入 API：配置读写（改仅 admin:user，读掩码）、连通性测试、AI 问诊对话。
 * chat/test 为 POST → 走全局 diag:execute 门槛（viewer 不可消耗模型配额）。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    public record ConfigReq(String baseUrl, String apiKey, String model, Double temperature, Double topP,
                            Integer maxTokens, String reasoningEffort, Integer timeoutSeconds, String systemPrompt,
                            String reasoningParams, String embedBaseUrl, String embedApiKey, String embedModel,
                            Integer embedDims) {
    }

    /** reasoningLevel：请求级思考强度（default/空=跟随接入配置；off/low/medium/high=ZCode 式会话覆盖） */
    public record ChatReq(String question, String instanceId, List<ChatTurn> history, String context,
                          String reasoningLevel) {
    }

    public record ConsultReq(String instanceId, String question, List<ChatTurn> history) {
    }

    private final LlmService llm;
    private final com.bianque.server.llm.EmbeddingService embeddings;
    private final com.bianque.server.llm.ConsultService consult;

    public LlmController(LlmService llm, com.bianque.server.llm.EmbeddingService embeddings,
                         com.bianque.server.llm.ConsultService consult) {
        this.llm = llm;
        this.embeddings = embeddings;
        this.consult = consult;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return llm.maskedConfig();
    }

    @PutMapping("/config")
    public Map<String, Object> update(@RequestBody ConfigReq req, HttpServletRequest request) {
        requireAdmin(request, "修改 LLM 接入配置");
        llm.update(req.baseUrl(), req.apiKey(), req.model(), req.temperature(), req.topP(),
                req.maxTokens(), req.reasoningEffort(), req.timeoutSeconds(), req.systemPrompt(),
                req.reasoningParams(), req.embedBaseUrl(), req.embedApiKey(), req.embedModel(), req.embedDims());
        return llm.maskedConfig();
    }

    /** 连通性测试（仅 admin）：用已保存配置发一句话，返回回复与耗时/用量 */
    @PostMapping("/test")
    public ChatOut test(@RequestBody(required = false) ChatReq req, HttpServletRequest request) {
        requireAdmin(request, "测试 LLM 连通性");
        String q = req != null && req.question() != null && !req.question().isBlank()
                ? req.question() : "用一句话回答：你是谁？具备什么能力？";
        String level = req == null ? null : req.reasoningLevel();
        return llm.chat(operator(request), null, q, List.of(), null, level);
    }

    /** 文档向量索引重建（仅 admin；同步执行，78 页约数秒到数十秒） */
    @PostMapping("/embeddings/reindex")
    public Map<String, Object> reindex(HttpServletRequest request) {
        requireAdmin(request, "重建文档向量索引");
        return embeddings.reindex();
    }

    /** 向量索引状态（已配置/块数/模型） */
    @GetMapping("/embeddings/status")
    public Map<String, Object> embeddingStatus() {
        return embeddings.status();
    }

    /**
     * AI 会诊（SSE 流式）：选中实例 + 自然语言 → 模型经 function calling 调平台工具执行分析。
     * 事件：start{consultId} → tool{步骤} / tool_result 已并入 tool / confirm_required{待审批命令}
     * → reasoning / content → done{content, steps} → error。写操作门槛 diag:execute 由拦截器统一保证。
     */
    @PostMapping(value = "/consult/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter consultStream(@RequestBody ConsultReq req, HttpServletRequest request,
                                    HttpServletResponse response) {
        if (req.question() == null || req.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        String operator = operator(request);
        SseEmitter emitter = new SseEmitter(300_000L);
        java.util.concurrent.atomic.AtomicInteger steps = new java.util.concurrent.atomic.AtomicInteger();
        com.bianque.server.llm.ConsultService.ConsultListener listener =
                new com.bianque.server.llm.ConsultService.ConsultListener() {
                    @Override
                    public void onTool(com.bianque.server.llm.ConsultService.ToolStep step) {
                        steps.set(step.step());
                        send(emitter, "tool", step);
                    }

                    @Override
                    public void onConfirmRequired(int step, String tool, String argsJson, String command) {
                        send(emitter, "confirm_required", Map.of("step", step, "tool", tool,
                                "argsJson", argsJson, "command", command));
                    }

                    @Override
                    public void onReasoning(String delta) {
                        send(emitter, "reasoning", Map.of("text", delta));
                    }

                    @Override
                    public void onContent(String delta) {
                        send(emitter, "content", Map.of("text", delta));
                    }

                    @Override
                    public void onDone(String content, java.util.List<com.bianque.server.llm.ConsultService.ToolStep> stepList) {
                        send(emitter, "done", Map.of("content", content == null ? "" : content,
                                "steps", stepList));
                        emitter.complete();
                    }

                    @Override
                    public void onError(String message) {
                        send(emitter, "error", Map.of("message", message));
                        emitter.complete();
                    }
                };
        String consultId = consult.start(operator, req.instanceId(), req.question(), req.history(), listener);
        send(emitter, "start", Map.of("consultId", consultId));
        return emitter;
    }

    /** 会诊审批决策（增强命令审批卡点「允许/拒绝」后调用） */
    @PostMapping("/consult/decision")
    public Map<String, Object> consultDecision(@RequestBody Map<String, Object> body) {
        String consultId = String.valueOf(body.getOrDefault("consultId", ""));
        int step = Integer.parseInt(String.valueOf(body.getOrDefault("step", "0")));
        boolean approved = Boolean.parseBoolean(String.valueOf(body.get("approved")));
        boolean ok = consult.decide(consultId, step, approved);
        return Map.of("ok", ok);
    }

    @PostMapping("/chat")
    public ChatOut chat(@RequestBody ChatReq req, HttpServletRequest request) {
        if (req.question() == null || req.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        return llm.chat(operator(request), req.instanceId(), req.question(), req.history(), req.context(),
                req.reasoningLevel());
    }

    /**
     * 对话（SSE 流式）：思考/回答增量实时下发（事件 reasoning/content，结束 done，失败 error）。
     * X-Accel-Buffering: no 关闭 Nginx 等反代缓冲，保证增量即时到达浏览器。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatReq req, HttpServletRequest request, HttpServletResponse response) {
        if (req.question() == null || req.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        LlmConfigEntity c = llm.config();
        long timeoutMs = ((c.timeoutSeconds == null ? 60 : c.timeoutSeconds) + 60) * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        llm.chatStreaming(operator(request), req.instanceId(), req.question(), req.history(), req.context(),
                req.reasoningLevel(), listener(emitter));
        return emitter;
    }

    private StreamListener listener(SseEmitter emitter) {
        return new StreamListener() {
            @Override
            public void onReasoning(String delta) {
                send(emitter, "reasoning", Map.of("text", delta));
            }

            @Override
            public void onContent(String delta) {
                send(emitter, "content", Map.of("text", delta));
            }

            @Override
            public void onDone(ChatOut out) {
                send(emitter, "done", out);
                emitter.complete();
            }

            @Override
            public void onError(String message) {
                send(emitter, "error", Map.of("message", message));
                emitter.complete();
            }
        };
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception ignore) {
            // 客户端断开/超时后的增量直接丢弃
        }
    }

    private void requireAdmin(HttpServletRequest request, String action) {
        Object auth = request.getAttribute(AuthInterceptor.ATTR);
        if (!(auth instanceof AuthContext a) || !a.hasPermission(AuthInterceptor.PermissionCodes.ADMIN_USER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "权限不足：仅管理员可" + action);
        }
    }

    private static String operator(HttpServletRequest request) {
        Object auth = request.getAttribute(AuthInterceptor.ATTR);
        return auth instanceof AuthContext a ? a.username() + "(" + a.displayName() + ")" : "system";
    }
}
