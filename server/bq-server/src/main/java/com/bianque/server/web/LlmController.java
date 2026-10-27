package com.bianque.server.web;

import com.bianque.server.auth.AuthContext;
import com.bianque.server.auth.AuthInterceptor;
import com.bianque.server.llm.LlmService;
import com.bianque.server.llm.LlmService.ChatOut;
import com.bianque.server.llm.LlmService.ChatTurn;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
                            Integer maxTokens, String reasoningEffort, Integer timeoutSeconds, String systemPrompt) {
    }

    public record ChatReq(String question, String instanceId, List<ChatTurn> history, String context) {
    }

    private final LlmService llm;

    public LlmController(LlmService llm) {
        this.llm = llm;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return llm.maskedConfig();
    }

    @PutMapping("/config")
    public Map<String, Object> update(@RequestBody ConfigReq req, HttpServletRequest request) {
        requireAdmin(request, "修改 LLM 接入配置");
        llm.update(req.baseUrl(), req.apiKey(), req.model(), req.temperature(), req.topP(),
                req.maxTokens(), req.reasoningEffort(), req.timeoutSeconds(), req.systemPrompt());
        return llm.maskedConfig();
    }

    /** 连通性测试（仅 admin）：用已保存配置发一句话，返回回复与耗时/用量 */
    @PostMapping("/test")
    public ChatOut test(@RequestBody(required = false) ChatReq req, HttpServletRequest request) {
        requireAdmin(request, "测试 LLM 连通性");
        String q = req != null && req.question() != null && !req.question().isBlank()
                ? req.question() : "用一句话回答：你是谁？具备什么能力？";
        return llm.chat(operator(request), null, q, List.of(), null);
    }

    @PostMapping("/chat")
    public ChatOut chat(@RequestBody ChatReq req, HttpServletRequest request) {
        if (req.question() == null || req.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        return llm.chat(operator(request), req.instanceId(), req.question(), req.history(), req.context());
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
