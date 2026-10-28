package com.bianque.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * S7 集成测试：LLM 接入（OpenAI Chat Completions 兼容）。
 * 用 JDK 内置 HttpServer 起一个假 OpenAI 端点，验证：未配置 409 / 权限门槛 /
 * 配置掩码 / 思考强度与采样参数的两套请求形态 / 对话回传 / 审计留痕。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:llmtest;MODE=MySQL")
class LlmIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static HttpServer fake;
    private static int fakePort;
    /** 假端点收到的最后一次请求体与 Authorization 头 */
    static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    @Autowired
    TestRestTemplate http;

    @BeforeAll
    static void startFakeOpenAi() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", ex -> {
            LAST_BODY.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            LAST_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = JSON.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of(
                            "role", "assistant",
                            "content", "测试回复：old gen 87%，建议扩容连接池。",
                            "reasoning_content", "（思考）先看线程状态分布，再对照内存水位。"))),
                    "usage", Map.of("prompt_tokens", 120, "completion_tokens", 48)));
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        fake.start();
        fakePort = fake.getAddress().getPort();
    }

    @AfterAll
    static void stopFake() {
        if (fake != null) {
            fake.stop(0);
        }
    }

    private ResponseEntity<Map> method(String url, HttpMethod m, Object body, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        return http.exchange(url, m, new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String login(String user, String pass) {
        ResponseEntity<Map> r = method("/api/auth/login", HttpMethod.POST,
                Map.of("username", user, "password", pass), null);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (String) r.getBody().get("token");
    }

    @Test
    @Order(1)
    void 未配置时对话返回409并给出指引() {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> r = method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "你好"), admin);
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertTrue(String.valueOf(r.getBody().get("message")).contains("LLM 未配置"));
    }

    @Test
    @Order(2)
    void 非管理员不能修改配置与测试连通() {
        String ops = login("ops", "ops123");
        assertEquals(HttpStatus.FORBIDDEN, method("/api/llm/config", HttpMethod.PUT,
                Map.of("model", "x"), ops).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, method("/api/llm/test", HttpMethod.POST,
                Map.of("question", "hi"), ops).getStatusCode());
    }

    @Test
    @Order(3)
    void 管理员保存配置且api_key只回传掩码() {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> put = method("/api/llm/config", HttpMethod.PUT, Map.of(
                "baseUrl", "http://127.0.0.1:" + fakePort + "/v1",
                "apiKey", "sk-test-9999",
                "model", "gpt-test",
                "temperature", 0.3,
                "topP", 0.9,
                "maxTokens", 512,
                "reasoningEffort", "LOW",
                "timeoutSeconds", 30,
                "systemPrompt", "你是测试助手。"), admin);
        assertEquals(HttpStatus.OK, put.getStatusCode(), "保存应成功: " + put.getBody());
        assertEquals(Boolean.TRUE, put.getBody().get("configured"));
        assertEquals(Boolean.TRUE, put.getBody().get("hasApiKey"));
        assertEquals("***9999", put.getBody().get("apiKeyMasked"), "api-key 必须掩码回传");
        assertFalse(String.valueOf(put.getBody()).contains("sk-test-9999"), "响应不得含明文 key");

        // 空 apiKey 提交 = 保留原值
        ResponseEntity<Map> keep = method("/api/llm/config", HttpMethod.PUT,
                Map.of("model", "gpt-test", "apiKey", ""), admin);
        assertEquals(Boolean.TRUE, keep.getBody().get("hasApiKey"));
    }

    @Test
    @Order(4)
    void 推理模式请求形态与对话回传() throws Exception {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> test = method("/api/llm/test", HttpMethod.POST,
                Map.of("question", "你好"), admin);
        assertEquals(HttpStatus.OK, test.getStatusCode(), "测试应成功");
        assertEquals("测试回复：old gen 87%，建议扩容连接池。", test.getBody().get("content"));
        assertEquals(120, test.getBody().get("promptTokens"));
        assertEquals("gpt-test", test.getBody().get("model"));

        // 校验发到假端点的请求：思考强度 + 推理模型约束
        JsonNode body = JSON.readTree(LAST_BODY.get());
        assertEquals("gpt-test", body.path("model").asText());
        assertEquals("low", body.path("reasoning_effort").asText());
        assertEquals(512, body.path("max_completion_tokens").asInt());
        assertFalse(body.has("temperature"), "推理模式不应发送 temperature");
        assertFalse(body.has("max_tokens"), "推理模式不应发送 max_tokens");
        assertEquals("system", body.path("messages").path(0).path("role").asText());
        assertTrue(body.path("messages").path(0).path("content").asText().contains("你是测试助手"));
        assertEquals("Bearer sk-test-9999", LAST_AUTH.get());
        // reasoning_content 单独回传（思考过程可见）
        assertEquals("（思考）先看线程状态分布，再对照内存水位。", test.getBody().get("reasoning"));
    }

    @Test
    @Order(5)
    void 普通模式请求形态与审计留痕() throws Exception {
        String admin = login("admin", "admin123");
        method("/api/llm/config", HttpMethod.PUT,
                Map.of("reasoningEffort", "OFF", "temperature", 0.5), admin);

        ResponseEntity<Map> chat = method("/api/llm/chat", HttpMethod.POST, Map.of(
                "question", "帮我看内存",
                "instanceId", "agent-x-1",
                "history", List.of(Map.of("role", "user", "content", "上一轮问题"),
                        Map.of("role", "assistant", "content", "上一轮回答")),
                "context", "memory 输出：heap used=40209840"), admin);
        assertEquals(HttpStatus.OK, chat.getStatusCode());
        assertEquals(48, chat.getBody().get("completionTokens"));

        JsonNode body = JSON.readTree(LAST_BODY.get());
        assertFalse(body.has("reasoning_effort"), "普通模式不应发送 reasoning_effort");
        assertEquals(0.5, body.path("temperature").asDouble(), 0.0001);
        assertEquals(512, body.path("max_tokens").asInt());
        assertEquals(4, body.path("messages").size(), "system + 历史2条 + 本轮");
        assertTrue(body.path("messages").path(0).path("content").asText().contains("诊断上下文"));

        // 审计留痕：AI 问诊来源 + 哈希链仍然有效
        ResponseEntity<Map> audit = http.exchange("/api/audit?page=0&size=30",
                HttpMethod.GET, new HttpEntity<>(authHeader(admin)), Map.class);
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        List<?> auditRecords = audit.getBody() == null ? null : (List<?>) audit.getBody().get("records");
        assertTrue(auditRecords != null && auditRecords.stream().anyMatch(e ->
                        "AI 问诊".equals(((Map<?, ?>) e).get("source"))
                                && String.valueOf(((Map<?, ?>) e).get("command")).startsWith("chat:")),
                "chat 与 test 都应留痕");
        ResponseEntity<Map> verify = method("/api/audit/verify", HttpMethod.GET, null, admin);
        assertEquals(Boolean.TRUE, verify.getBody().get("valid"), "AI 审计入链后哈希链必须完整");
    }

    @Test
    @Order(6)
    void viewer不能消耗模型配额() {
        String viewer = login("viewer", "viewer123");
        assertEquals(HttpStatus.FORBIDDEN, method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "hi"), viewer).getStatusCode());
    }

    private HttpHeaders authHeader(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
