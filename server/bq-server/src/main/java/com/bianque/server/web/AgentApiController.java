package com.bianque.server.web;

import com.bianque.server.agent.AgentCommandService;
import com.bianque.server.agent.AgentDirectory;
import com.bianque.server.service.SysConfigService;
import com.bianque.server.store.AgentCommandEntity;
import com.bianque.server.store.AgentNodeEntity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 平台 Agent 节点接入 API（/api/agent/**，X-Agent-Token 认证，无平台登录态）：
 * 注册 → 心跳（上报进程 + 领单）→ 回传结果；另提供节点程序包下载。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentApiController {

    private final AgentDirectory directory;
    private final AgentCommandService commands;
    private final SysConfigService config;
    private final String token;
    private final String jarPath;

    public AgentApiController(AgentDirectory directory, AgentCommandService commands,
                              SysConfigService config,
                              @Value("${bianque.agent.token:}") String token,
                              @Value("${bianque.agent.jar-path:agents/bq-agent.jar}") String jarPath) {
        this.directory = directory;
        this.commands = commands;
        this.config = config;
        this.token = token;
        this.jarPath = jarPath;
    }

    public record RegisterReq(String agentId, String agentVersion, String host, String hostname,
                              String os, String javaVersion) {
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq req, HttpServletRequest request) {
        requireToken(request);
        String host = firstNonBlank(req.host(), request.getRemoteAddr());
        AgentNodeEntity e = directory.register(req.agentId(), host, firstNonBlank(req.hostname(), host),
                req.os(), req.javaVersion(), req.agentVersion());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("agentId", e.agentId);
        resp.put("heartbeatIntervalSeconds", config.intValue("agent.heartbeat-interval-seconds"));
        return resp;
    }

    /** 心跳：上报进程清单，服务端同步机队并派发下一单；响应携带心跳节奏（探针动态跟随） */
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestBody JsonNode body, HttpServletRequest request) {
        requireToken(request);
        String agentId = body.path("agentId").asText("");
        if (!directory.exists(agentId)) {
            throw new ResponseStatusException(HttpStatus.GONE, "节点未注册或已被清理，请重新 register");
        }
        directory.heartbeat(agentId, body.path("host").asText(null), pids(body), mainClasses(body));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("heartbeatIntervalSeconds", config.intValue("agent.heartbeat-interval-seconds"));
        AgentCommandEntity next = commands.claimNext(agentId);
        if (next != null) {
            resp.put("command", Map.of(
                    "id", next.id,
                    "instanceId", next.instanceId,
                    "command", next.command));
        } else {
            resp.put("command", null);
        }
        return resp;
    }

    public record ResultReq(String status, String output, String outputKind, String error, Long costMs) {
    }

    @PostMapping("/commands/{id}/result")
    public Map<String, Object> result(@PathVariable long id, @RequestBody ResultReq req,
                                      HttpServletRequest request) {
        requireToken(request);
        String status = "FAILED".equalsIgnoreCase(req.status()) ? AgentCommandEntity.FAILED : AgentCommandEntity.DONE;
        commands.submitResult(id, status, req.output(),
                req.outputKind() == null ? "text" : req.outputKind(), req.error(),
                req.costMs() == null ? 0 : req.costMs());
        return Map.of("ok", true);
    }

    /** 节点程序包下载：配置令牌后需带 X-Agent-Token（与其他 /api/agent/** 一致） */
    @GetMapping("/download")
    public ResponseEntity<FileSystemResource> download(HttpServletRequest request) {
        requireToken(request);
        File f = new File(jarPath);
        if (!f.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "节点程序包未就绪：请将 bq-agent.jar 拷贝至 " + jarPath);
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=bq-agent.jar")
                .contentType(MediaType.parseMediaType("application/java-archive"))
                .body(new FileSystemResource(f));
    }

    // ---- helpers ----

    private void requireToken(HttpServletRequest request) {
        if (token == null || token.isBlank()) {
            return; // 未配置令牌：仅限内网演示环境开放
        }
        String given = request.getHeader("X-Agent-Token");
        if (!token.equals(given)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Agent-Token 缺失或不匹配");
        }
    }

    private static List<long[]> pids(JsonNode body) {
        List<long[]> out = new ArrayList<>();
        for (JsonNode p : body.path("processes")) {
            out.add(new long[]{p.path("pid").asLong()});
        }
        return out;
    }

    private static List<String> mainClasses(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : body.path("processes")) {
            out.add(p.path("mainClass").asText("unknown"));
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
