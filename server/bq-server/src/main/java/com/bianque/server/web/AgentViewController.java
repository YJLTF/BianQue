package com.bianque.server.web;

import com.bianque.server.agent.AgentDirectory;
import com.bianque.server.auth.AuthContext;
import com.bianque.server.auth.AuthInterceptor;
import com.bianque.server.repo.AuditTrail;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 节点管理视图 API（登录态）：节点清单 + 接入指引（令牌仅 admin:user 可见）+ 删除离线节点。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentViewController {

    private final AgentDirectory directory;
    private final ObjectProvider<AuditTrail> audit;
    private final String token;

    public AgentViewController(AgentDirectory directory, ObjectProvider<AuditTrail> audit,
                               @Value("${bianque.agent.token:}") String token) {
        this.directory = directory;
        this.audit = audit;
        this.token = token;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<AgentDirectory.NodeView> nodes = directory.listView();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("nodes", nodes);
        resp.put("total", nodes.size());
        resp.put("online", nodes.stream().filter(AgentDirectory.NodeView::online).count());
        resp.put("processes", nodes.stream().mapToLong(n -> n.processes().size()).sum());
        return resp;
    }

    /** 删除节点（仅 admin:user）：只允许删除离线节点，台账/内存进程表/机队实例一并清理并审计 */
    @DeleteMapping("/{agentId}")
    public Map<String, Object> remove(@PathVariable String agentId, HttpServletRequest request) {
        Object auth = request.getAttribute(AuthInterceptor.ATTR);
        if (!(auth instanceof AuthContext a) || !a.hasPermission(AuthInterceptor.PermissionCodes.ADMIN_USER)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "权限不足：仅管理员可删除节点");
        }
        if (!directory.exists(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "节点不存在：" + agentId);
        }
        if (directory.online(agentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "节点在线，仅支持删除离线节点");
        }
        int removed = directory.removeNode(agentId);
        String operator = a.username() + "(" + a.displayName() + ")";
        try {
            audit.ifAvailable(t -> t.append("节点管理", operator, agentId,
                    "删除离线节点（摘除实例 " + removed + "）", 0, "ok"));
        } catch (Exception ignore) {
            // 审计失败不阻断删除
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("deleted", agentId);
        resp.put("instancesRemoved", removed);
        return resp;
    }

    /** 接入指引：下载地址 + 启动命令（token 仅管理员可见） */
    @GetMapping("/install")
    public Map<String, Object> install(HttpServletRequest request) {
        String serverUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        boolean tokenConfigured = token != null && !token.isBlank();
        Object auth = request.getAttribute(AuthInterceptor.ATTR);
        boolean admin = auth instanceof AuthContext a && a.hasPermission(AuthInterceptor.PermissionCodes.ADMIN_USER);
        String tokenShown = tokenConfigured ? (admin ? token : "<需 admin 权限查看>") : "<未配置：演示模式可省略>";

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("serverUrl", serverUrl);
        resp.put("tokenConfigured", tokenConfigured);
        resp.put("token", admin ? token : null);
        resp.put("downloadUrl", serverUrl + "/api/agent/download");
        List<String> steps = new ArrayList<>();
        steps.add("curl -o bq-agent.jar \"" + serverUrl + "/api/agent/download\"");
        steps.add("将 arthas-boot.jar 与 bq-agent.jar 放在同一目录");
        steps.add("java -jar bq-agent.jar --server " + serverUrl + " --token " + tokenShown);
        resp.put("steps", steps);
        resp.put("sampleCommand", "java -jar bq-agent.jar --server " + serverUrl + " --token " + tokenShown);
        return resp;
    }
}
