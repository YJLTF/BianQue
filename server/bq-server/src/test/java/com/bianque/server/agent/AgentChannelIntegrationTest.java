package com.bianque.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * S6 集成测试：平台 Agent 接入闭环（模拟一个 bq-agent 节点走真实 HTTP）。
 * 注册 → 心跳上报进程 → 机队出现 agent 实例 → 诊断命令派单/回传 → 会话关闭下发 stop。
 * 节点侧统一用 JDK HttpClient（与真实 bq-agent 一致；TestRestTemplate 对 401 POST 有重试缺陷）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agenttest;MODE=MySQL",
        "bianque.agent.token=agent-token-123"
})
class AgentChannelIntegrationTest {

    private static final String TOKEN = "agent-token-123";
    /** 模拟节点上报的目标进程 */
    private static final long PID = 47113;
    private static final String MAIN_CLASS = "demo.math.Game";
    private static final String PROCS_JSON =
            "[{\"pid\":" + PID + ",\"mainClass\":\"" + MAIN_CLASS + "\"}]";

    @Autowired
    TestRestTemplate http;

    @LocalServerPort
    int port;

    private final HttpClient raw = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    static String agentId;

    // ---- 节点侧（JDK HttpClient，X-Agent-Token） ----

    private record Raw(int status, String body) {
    }

    private Raw rawPost(String path, String json, String agentToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (agentToken != null) {
            b.header("X-Agent-Token", agentToken);
        }
        HttpResponse<String> r = raw.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Raw(r.statusCode(), r.body());
    }

    private Raw rawPostWithBearer(String path, String json, String agentToken, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (agentToken != null) {
            b.header("X-Agent-Token", agentToken);
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> r = raw.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Raw(r.statusCode(), r.body());
    }

    /** 节点心跳：返回响应里的 command（可能为 null） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> heartbeat(String where) {
        try {
            Raw r = rawPost("/api/agent/heartbeat",
                    "{\"agentId\":\"" + agentId + "\",\"host\":\"10.8.0.16\",\"processes\":" + PROCS_JSON + "}",
                    TOKEN);
            assertEquals(200, r.status(), where + " 心跳应成功: " + r.body());
            Object cmd = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(r.body(), Map.class).get("command");
            return (Map<String, Object>) cmd;
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(where + " 心跳异常", e);
        }
    }

    private void submitResult(String where, long id, String status, String output, String outputKind) {
        try {
            Raw r = rawPost("/api/agent/commands/" + id + "/result",
                    "{\"status\":\"" + status + "\",\"output\":" +
                            com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                                    .writeValueAsString(output) +
                            ",\"outputKind\":\"" + outputKind + "\",\"costMs\":12}", TOKEN);
            assertEquals(200, r.status(), where + " 结果回传应成功: " + r.body());
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(where + " 回传异常", e);
        }
    }

    // ---- 用户侧（TestRestTemplate，Bearer） ----

    private ResponseEntity<Map> post(String url, Object body, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<Map> get(String url, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private ResponseEntity<List> getList(String url, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), List.class);
    }

    @SuppressWarnings("unchecked")
    private String login(String user, String pass) {
        ResponseEntity<Map> r = post("/api/auth/login", Map.of("username", user, "password", pass), null);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (String) r.getBody().get("token");
    }

    @Test
    @Order(1)
    void 未携带节点令牌的接入请求被拒绝() throws Exception {
        assertEquals(401, rawPost("/api/agent/register", "{\"hostname\":\"x\"}", null).status());
        assertEquals(401, rawPost("/api/agent/heartbeat",
                "{\"agentId\":\"x\",\"processes\":" + PROCS_JSON + "}", "wrong-token").status());
        // 节点令牌与平台登录态相互独立：带用户 Bearer 但缺节点令牌同样拒绝
        String user = login("admin", "admin123");
        assertEquals(401, rawPostWithBearer("/api/agent/heartbeat",
                "{\"agentId\":\"x\",\"processes\":" + PROCS_JSON + "}", null, user).status(),
                "用户登录态不能替代节点令牌");
    }

    @Test
    @Order(2)
    void 注册与心跳上线并同步机队实例() throws Exception {
        Raw reg = rawPost("/api/agent/register",
                "{\"agentVersion\":\"0.1.0\",\"host\":\"10.8.0.16\",\"hostname\":\"node-a\","
                        + "\"os\":\"Linux 5.15\",\"javaVersion\":\"17\"}", TOKEN);
        assertEquals(200, reg.status(), "注册应成功: " + reg.body());
        agentId = String.valueOf(new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(reg.body(), Map.class).get("agentId"));
        assertNotNull(agentId);

        Map<String, Object> cmd = heartbeat("首次");
        assertNull(cmd, "无积压命令时心跳应答为空命令: " + cmd);
        // 未注册节点的心跳应被拒绝
        assertEquals(410, rawPost("/api/agent/heartbeat",
                "{\"agentId\":\"ghost-1\",\"processes\":" + PROCS_JSON + "}", TOKEN).status());

        // 登录侧：节点目录可见、机队出现 agent 实例
        String user = login("admin", "admin123");
        ResponseEntity<Map> nodes = get("/api/agents", user);
        assertEquals(HttpStatus.OK, nodes.getStatusCode());
        List<Map<String, Object>> list = (List<Map<String, Object>>) nodes.getBody().get("nodes");
        assertEquals(1, list.size());
        assertEquals(Boolean.TRUE, list.get(0).get("online"));

        ResponseEntity<List> instances = getList("/api/fleet/instances", user);
        assertEquals(HttpStatus.OK, instances.getStatusCode());
        List<?> fleetList = instances.getBody();
        assertTrue(fleetList.stream().anyMatch(i ->
                        AgentDirectory.instanceId(agentId, PID).equals(((Map<?, ?>) i).get("instanceId"))),
                "机队应出现 agent 实例: " + fleetList);
    }

    @Test
    @Order(3)
    void 诊断命令经命令单派发并渲染回传() throws Exception {
        String user = login("admin", "admin123");
        String instanceId = AgentDirectory.instanceId(agentId, PID);

        AtomicBoolean nodeWorkDone = new AtomicBoolean(false);
        AtomicReference<Throwable> nodeError = new AtomicReference<>();
        Thread node = new Thread(() -> {
            // 模拟节点：心跳领单 → 回传 arthas agent 原始 JSON
            try {
                for (int i = 0; i < 200 && !nodeWorkDone.get(); i++) {
                    Map<String, Object> cmd = heartbeat("领单循环#" + i);
                    if (cmd != null && "memory".equals(cmd.get("command"))) {
                        submitResult("派单回传", ((Number) cmd.get("id")).longValue(), "DONE",
                                "{\"body\":{\"results\":[{\"type\":\"version\",\"version\":\"4.3.5\"}]}}",
                                "agent-json");
                        nodeWorkDone.set(true);
                    }
                    Thread.sleep(150);
                }
            } catch (Throwable t) {
                nodeError.set(t);
            }
        }, "it-fake-agent");
        node.start();

        ResponseEntity<Map> exec = post("/api/diag/execute",
                Map.of("instanceId", instanceId, "command", "memory", "source", "人工"), user);
        node.join(35_000);

        assertNull(nodeError.get(), "模拟节点线程不应报错: " + nodeError.get());
        assertEquals(HttpStatus.OK, exec.getStatusCode(), "执行应成功");
        assertTrue((Boolean) exec.getBody().get("success"),
                "执行应成功，完整响应: " + exec.getBody());
        assertEquals("real", exec.getBody().get("channel"), "agent 实例应标记真实通道");
        assertTrue(String.valueOf(exec.getBody().get("output")).contains("version: 4.3.5"),
                "agent-json 应被平台渲染: " + exec.getBody());
        assertTrue(nodeWorkDone.get(), "模拟节点应领到命令单");
    }

    @Test
    @Order(4)
    void 会话关闭向节点下发stop() {
        String user = login("admin", "admin123");
        String instanceId = AgentDirectory.instanceId(agentId, PID);
        ResponseEntity<Map> session = get("/api/diag/session/" + instanceId, user);
        assertEquals(Boolean.TRUE, session.getBody().get("active"), "上一用例应留下活跃会话");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(user);
        ResponseEntity<Map> close = http.exchange("/api/diag/session/" + session.getBody().get("sessionId"),
                HttpMethod.DELETE, new HttpEntity<>(h), Map.class);
        assertEquals(HttpStatus.OK, close.getStatusCode());

        // 节点随后心跳应领到 __close__ 命令
        Map<String, Object> cmd = null;
        for (int i = 0; i < 50 && cmd == null; i++) {
            cmd = heartbeat("关停领单#" + i);
            if (cmd != null && !"__close__".equals(cmd.get("command"))) {
                submitResult("关停杂单", ((Number) cmd.get("id")).longValue(), "DONE", "", "text");
                cmd = null;
            }
        }
        assertNotNull(cmd, "应领到 __close__ 命令");
        assertEquals(instanceId, cmd.get("instanceId"));
        submitResult("关停回传", ((Number) cmd.get("id")).longValue(), "DONE", "会话已回收", "text");
    }

    @Test
    @Order(5)
    void 节点程序包未放置时下载返回404() {
        ResponseEntity<String> dl = http.exchange("/api/agent/download", HttpMethod.GET,
                HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.NOT_FOUND, dl.getStatusCode());
    }

    private ResponseEntity<Map> delete(String url, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        return http.exchange(url, HttpMethod.DELETE, new HttpEntity<>(h), Map.class);
    }

    private void registerNode(String agentId) throws Exception {
        Raw r = rawPost("/api/agent/register",
                "{\"agentId\":\"" + agentId + "\",\"agentVersion\":\"1.0.0\",\"host\":\"127.0.0.1\","
                        + "\"hostname\":\"" + agentId + "\",\"os\":\"Linux\",\"javaVersion\":\"17\"}",
                TOKEN);
        assertEquals(200, r.status(), "注册应成功: " + r.body());
    }

    @Autowired
    com.bianque.server.store.AgentNodeJpa nodeRepo;

    /** 注册即置 lastHeartbeat=now（20s 内算在线）；回拨 60s 模拟离线，避免测试睡眠 */
    private void backdate(String agentId) {
        com.bianque.server.store.AgentNodeEntity e = nodeRepo.findById(agentId).orElseThrow();
        e.lastHeartbeat = java.time.Instant.now().minusSeconds(60);
        nodeRepo.save(e);
    }

    @Test
    @Order(6)
    void 仅管理员可删除离线节点且在线节点拒绝删除() throws Exception {
        String admin = login("admin", "admin123");
        String viewer = login("viewer", "viewer123");

        // 权限门槛：非管理员 403
        registerNode("tmp-del-offline");
        backdate("tmp-del-offline");
        assertEquals(HttpStatus.FORBIDDEN,
                delete("/api/agents/tmp-del-offline", viewer).getStatusCode());

        // 删除离线节点成功 → 再删 404
        ResponseEntity<Map> ok = delete("/api/agents/tmp-del-offline", admin);
        assertEquals(HttpStatus.OK, ok.getStatusCode(), "删除离线节点应成功: " + ok.getBody());
        assertEquals("tmp-del-offline", ok.getBody().get("deleted"));
        assertEquals(HttpStatus.NOT_FOUND, delete("/api/agents/tmp-del-offline", admin).getStatusCode());

        // 在线节点（注册后立即心跳）拒绝删除
        registerNode("tmp-del-online");
        rawPost("/api/agent/heartbeat", "{\"agentId\":\"tmp-del-online\",\"host\":\"127.0.0.1\",\"processes\":[]}", TOKEN);
        assertEquals(HttpStatus.CONFLICT, delete("/api/agents/tmp-del-online", admin).getStatusCode(),
                "在线节点禁止删除");

        // 列表不再含已删节点，在线节点保留
        ResponseEntity<Map> list = get("/api/agents", admin);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) list.getBody().get("nodes");
        assertTrue(nodes.stream().noneMatch(n -> "tmp-del-offline".equals(n.get("agentId"))),
                "已删节点不应出现在列表");
        assertTrue(nodes.stream().anyMatch(n -> "tmp-del-online".equals(n.get("agentId"))),
                "在线节点应保留");
    }
}
