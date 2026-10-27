package com.bianque.server.web;

import com.bianque.core.diag.DiagResult;
import com.bianque.server.auth.AuthContext;
import com.bianque.server.auth.AuthInterceptor;
import com.bianque.server.service.DiagService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 诊断执行 API（唯一执行入口）：高危拦截、会话托管、审计留痕。
 */
@RestController
@RequestMapping("/api/diag")
public class DiagController {

    public record ExecuteRequest(String instanceId, String command, String source) {
    }

    private final DiagService diag;
    private final com.bianque.server.repo.FleetRepository fleet;

    public DiagController(DiagService diag, com.bianque.server.repo.FleetRepository fleet) {
        this.diag = diag;
        this.fleet = fleet;
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody ExecuteRequest req, HttpServletRequest request) {
        String src = req.source() == null || req.source().isBlank() ? "人工" : req.source();
        DiagResult out = diag.execute(req.instanceId(), req.command(), src, operator(request));
        var ins = fleet.find(req.instanceId()).orElse(null);
        String channel = ins != null && ins.localAgent() ? "real" : "simulated";
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("success", out.success());
        resp.put("output", out.output() == null ? "" : out.output());
        resp.put("error", out.error() == null ? "" : out.error());
        resp.put("costMs", out.costMs());
        resp.put("channel", channel);
        return resp;
    }

    @GetMapping("/session/{instanceId}")
    public Object session(@PathVariable String instanceId) {
        var s = diag.activeSessionOf(instanceId);
        return s == null ? Map.of("active", false) : s;
    }

    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> close(@PathVariable String sessionId) {
        return Map.of("closed", diag.closeSession(sessionId) != null);
    }

    static String operator(HttpServletRequest request) {
        Object auth = request.getAttribute(AuthInterceptor.ATTR);
        return auth instanceof AuthContext a ? a.username() + "(" + a.displayName() + ")" : DiagService.SYSTEM;
    }
}
