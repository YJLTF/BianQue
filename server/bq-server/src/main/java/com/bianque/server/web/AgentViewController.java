package com.bianque.server.web;

import com.bianque.server.agent.AgentDirectory;
import com.bianque.server.auth.AuthContext;
import com.bianque.server.auth.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节点管理视图 API（登录态）：节点清单 + 接入指引（令牌仅 admin:user 可见）。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentViewController {

    private final AgentDirectory directory;
    private final String token;

    public AgentViewController(AgentDirectory directory,
                               @Value("${bianque.agent.token:}") String token) {
        this.directory = directory;
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
