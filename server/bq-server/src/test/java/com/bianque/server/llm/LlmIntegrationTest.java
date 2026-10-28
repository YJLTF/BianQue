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
            String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LAST_BODY.set(reqBody);
            LAST_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
            boolean stream = JSON.readTree(reqBody).path("stream").asBoolean(false);
            if (stream) {
                // SSE 流式形态：reasoning 先流出 → content 分帧 → usage 尾帧 → [DONE]
                StringBuilder sb = new StringBuilder();
                sb.append(sse(Map.of("choices", List.of(Map.of(
                        "delta", Map.of("role", "assistant", "reasoning_content", "（思考）线程分布正常，再看内存。"))))));
                sb.append(sse(Map.of("choices", List.of(Map.of("delta", Map.of("content", "测试"))))));
                sb.append(sse(Map.of("choices", List.of(Map.of("delta", Map.of("content", "流式回复。"))))));
                sb.append(sse(Map.of("choices", List.of(Map.of("delta", Map.of()))),
                        Map.of("prompt_tokens", 120, "completion_tokens", 48)));
                sb.append("data: [DONE]\n\n");
                byte[] resp = sb.toString().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                ex.sendResponseHeaders(200, resp.length);
                ex.getResponseBody().write(resp);
                ex.close();
                return;
            }
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

    private static String sse(Map<?, ?> chunk) {
        return sse(chunk, null);
    }

    private static String sse(Map<?, ?> chunk, Map<?, ?> usage) {
        var obj = new java.util.LinkedHashMap<Object, Object>(chunk);
        if (usage != null) {
            obj.put("usage", usage);
        }
        try {
            return "data: " + JSON.writeValueAsString(obj) + "\n\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    @Test
    @Order(7)
    void 对话自动检索知识库注入提示词并回传引用() throws Exception {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> chat = method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "watch 命令怎么用？观察方法出参有什么限制？"), admin);
        assertEquals(HttpStatus.OK, chat.getStatusCode());

        // 发往 LLM 的请求应注入知识库片段（watch 页内容）
        JsonNode body = JSON.readTree(LAST_BODY.get());
        String sys = body.path("messages").path(0).path("content").asText();
        assertTrue(sys.contains("Arthas 知识库参考"), "system 应注入知识库段：" + sys.substring(0, Math.min(200, sys.length())));
        assertTrue(sys.contains("watch"), "命中片段应包含 watch 文档内容");

        // 引用随响应回传，供前端溯源跳转
        List<?> refs = (List<?>) chat.getBody().get("refs");
        assertTrue(refs != null && !refs.isEmpty(), "应回传参考文档列表");
        assertTrue(String.valueOf(refs.get(0)).contains("watch"), "首条引用应为 watch 页：" + refs.get(0));
    }

    @Test
    @Order(8)
    void 无关问题不注入知识库() throws Exception {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> chat = method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "今天天气怎么样"), admin);
        assertEquals(HttpStatus.OK, chat.getStatusCode());
        JsonNode body = JSON.readTree(LAST_BODY.get());
        assertFalse(body.path("messages").path(0).path("content").asText().contains("知识库参考"),
                "无命中时不应注入知识库段");
        List<?> refs = (List<?>) chat.getBody().get("refs");
        assertTrue(refs == null || refs.isEmpty(), "无命中时引用应为空");
    }

    @Test
    @Order(9)
    void 请求级思考强度可覆盖接入配置() throws Exception {
        String admin = login("admin", "admin123");
        // 配置处于 OFF（Order(5) 已置），本条对话请求级 high → 推理形态
        ResponseEntity<Map> chat = method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "帮我看线程", "reasoningLevel", "high"), admin);
        assertEquals(HttpStatus.OK, chat.getStatusCode());
        JsonNode body = JSON.readTree(LAST_BODY.get());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertEquals(512, body.path("max_completion_tokens").asInt());
        assertFalse(body.has("temperature"), "请求级 high 不应带 temperature");

        // 非法等级 → 400
        assertEquals(HttpStatus.BAD_REQUEST, method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "hi", "reasoningLevel", "turbo"), admin).getStatusCode());
        // default = 跟随配置（OFF → 普通形态）
        method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "hi", "reasoningLevel", "default"), admin);
        assertFalse(JSON.readTree(LAST_BODY.get()).has("reasoning_effort"));
    }

    @Test
    @Order(10)
    void 推理参数映射按等级深度覆盖请求体() throws Exception {
        String admin = login("admin", "admin123");
        String mapping = "{\"off\":{\"thinking\":{\"type\":\"disabled\"}},"
                + "\"high\":{\"reasoning_effort\":\"high\",\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}}}";
        ResponseEntity<Map> put = method("/api/llm/config", HttpMethod.PUT,
                Map.of("reasoningParams", mapping), admin);
        assertEquals(HttpStatus.OK, put.getStatusCode(), "映射应保存: " + put.getBody());
        assertEquals(Boolean.TRUE, put.getBody().get("configured"));
        // 非法 JSON → 400，且不影响原值
        assertEquals(HttpStatus.BAD_REQUEST, method("/api/llm/config", HttpMethod.PUT,
                Map.of("reasoningParams", "not-json"), admin).getStatusCode());

        // off 等级：映射片段深度覆盖（thinking.disabled），max_tokens 兜底回填
        method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "hi", "reasoningLevel", "off"), admin);
        JsonNode body = JSON.readTree(LAST_BODY.get());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertFalse(body.has("reasoning_effort"), "off 片段不应含 reasoning_effort");
        assertFalse(body.has("temperature"), "命中映射时采样参数归片段所有");
        assertEquals(512, body.path("max_tokens").asInt(), "片段未给 max_tokens 时应回填");

        // high 等级：映射片段 + reasoning_effort + max_tokens 兜底
        method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "hi", "reasoningLevel", "high"), admin);
        body = JSON.readTree(LAST_BODY.get());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals(2048, body.path("thinking").path("budget_tokens").asInt());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertEquals(512, body.path("max_tokens").asInt());

        // 清空映射（空串）→ 回内置形态
        method("/api/llm/config", HttpMethod.PUT, Map.of("reasoningParams", ""), admin);
        method("/api/llm/chat", HttpMethod.POST,
                Map.of("question", "hi", "reasoningLevel", "off"), admin);
        body = JSON.readTree(LAST_BODY.get());
        assertFalse(body.has("thinking"), "清空映射后 off 应回内置普通形态");
        assertTrue(body.has("temperature"));
    }

    @Test
    @Order(11)
    void SSE流式输出思考与回答增量() {
        String admin = login("admin", "admin123");
        ResponseEntity<String> sse = http.exchange("/api/llm/chat/stream", HttpMethod.POST,
                new HttpEntity<>(Map.of("question", "你好", "reasoningLevel", "high"), authHeader(admin)),
                String.class);
        assertEquals(HttpStatus.OK, sse.getStatusCode(), "流式端点应 200");
        assertTrue(sse.getHeaders().getContentType().toString().contains("text/event-stream"),
                "应为 SSE： " + sse.getHeaders().getContentType());
        String body = sse.getBody() == null ? "" : sse.getBody();
        assertTrue(body.contains("event:reasoning"), "应有思考增量事件: " + body);
        assertTrue(body.contains("（思考）线程分布正常"), "思考文本应随帧下发");
        assertTrue(body.contains("event:content"), "应有回答增量事件");
        assertTrue(body.contains("测试"), "回答文本应随帧下发");
        assertTrue(body.contains("event:done"), "应有收尾汇总事件");
        assertTrue(body.contains("\"promptTokens\":120"), "done 汇总应含 usage: " + body);
    }

    private HttpHeaders authHeader(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
