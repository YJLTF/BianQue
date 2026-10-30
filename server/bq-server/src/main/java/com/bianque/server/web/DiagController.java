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
import org.springframework.http.ResponseEntity;

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
    private final com.bianque.server.service.ProfileService profile;
    private final com.bianque.server.service.CompareService compare;
    private final com.bianque.server.store.DiagArtifactJpa artifacts;
    private final com.bianque.server.auth.ArtifactTickets artifactTickets;

    public DiagController(DiagService diag, com.bianque.server.repo.FleetRepository fleet,
                          com.bianque.server.service.ProfileService profile,
                          com.bianque.server.service.CompareService compare,
                          com.bianque.server.store.DiagArtifactJpa artifacts,
                          com.bianque.server.auth.ArtifactTickets artifactTickets) {
        this.diag = diag;
        this.fleet = fleet;
        this.profile = profile;
        this.compare = compare;
        this.artifacts = artifacts;
        this.artifactTickets = artifactTickets;
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
        // 结构化指标（thread/memory/jvm/dashboard/monitor 可解析时附带；不可解析为 null，前端回退文本视图）
        resp.put("metrics", com.bianque.core.diag.ResultParsers.parse(req.command(), out.output()));
        return resp;
    }

    // ---- 性能剖析（火焰图）：profiler 经命令通道，产物 cat 回传落库后同源 iframe 渲染 ----

    @PostMapping("/profile/start")
    public Map<String, Object> profileStart(@RequestBody Map<String, String> body, HttpServletRequest request) {
        return profile.start(body.get("instanceId"), operator(request));
    }

    @PostMapping("/profile/stop")
    public Map<String, Object> profileStop(@RequestBody Map<String, String> body, HttpServletRequest request) {
        return profile.stop(body.get("instanceId"), operator(request));
    }

    /** 工件内容（text/html，登录态门禁，同源 iframe 可嵌） */
    @GetMapping("/artifact/{id}")
    public ResponseEntity<String> artifact(@PathVariable long id) {
        var a = artifacts.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "工件不存在或已过期清理"));
        return ResponseEntity.ok()
                .header("Content-Type", "text/html; charset=utf-8")
                .header("X-Content-Type-Options", "nosniff")
                .body(a.content == null ? "" : a.content);
    }

    /** 签发产物查看票据（一次性/60s/单工件）：iframe 无法携带 Bearer 头，
        blob 载荷又会继承页面 CSP 被浏览器拦截（frame-src/script-src）——同源 URL + 短票据两全 */
    @GetMapping("/artifact/{id}/ticket")
    public Map<String, Object> artifactTicket(@PathVariable long id) {
        artifacts.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "工件不存在或已过期清理"));
        return Map.of("ticket", artifactTickets.issue(id), "url", "/api/diag/artifact/" + id);
    }

    // ---- 双实例基线对比（火山图）----

    public record CompareReq(String targetInstanceId, String baseInstanceId, Integer rounds, Integer intervalMs) {
    }

    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody CompareReq req, HttpServletRequest request) {
        return compare.compare(new com.bianque.server.service.CompareService.CompareReq(
                req.targetInstanceId(), req.baseInstanceId(), req.rounds(), req.intervalMs()),
                operator(request));
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
