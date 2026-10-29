package com.bianque.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.server.store.AiToolRunEntity;
import com.bianque.server.store.AiToolRunJpa;
import com.bianque.server.store.LlmConfigEntity;
import com.bianque.server.store.LlmConfigJpa;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * AI 会诊集成测试：脚本化假上游（工具调用序列）驱动完整循环——只读命令自动执行、
 * 黑名单拒绝、增强命令审批（批准/拒绝/超时）、步数上限、ai_tool_run 留痕。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:consulttest;MODE=MySQL",
})
class ConsultIntegrationTest {

    @Autowired
    ConsultService consult;

    @Autowired
    AiToolRunJpa toolRuns;

    @Autowired
    LlmConfigJpa cfgRepo;

    private static HttpServer fake;

    /** 脚本队列：每次上游调用弹一条预置响应 */
    private static final List<String> SCRIPT = new CopyOnWriteArrayList<>();

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** OpenAI 协议 arguments 是 JSON 字符串（非对象）——转义嵌入 */
    private static String toolCall(String id, String fn, String argsJson) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + fn
                + "\",\"arguments\":\"" + escape(argsJson) + "\"}}";
    }

    private static String toolResp(String... calls) {
        StringBuilder sb = new StringBuilder("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[");
        for (int i = 0; i < calls.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(calls[i]);
        }
        return sb.append("]}}]}").toString();
    }

    private static String textResp(String text) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}";
    }

    @BeforeAll
    void startFakeAndConfigure() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/chat/completions", ex -> {
            String body = SCRIPT.isEmpty() ? textResp("脚本已耗尽") : SCRIPT.remove(0);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        LlmConfigEntity e = cfgRepo.findById(1L).orElseGet(() -> cfgRepo.save(new LlmConfigEntity()));
        e.baseUrl = "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1";
        e.apiKey = "sk-fake";
        e.model = "fake-chat";
        cfgRepo.save(e);
    }

    @AfterAll
    void stopFake() {
        fake.stop(0);
    }

    private String adminToken() {
        return String.valueOf(((Map<?, ?>) http().postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class).getBody()).get("token"));
    }

    /** 事件收集器（增强命令审批：按 approveCommands 决定批准/拒绝） */
    private static final class Collector implements ConsultService.ConsultListener {
        final List<ConsultService.ToolStep> tools = new ArrayList<>();
        final List<String> contents = new ArrayList<>();
        final List<String> confirms = new ArrayList<>();
        volatile String doneContent;
        volatile String error;
        volatile boolean released;
        List<String> approveCommands = List.of();

        @Override
        public void onTool(ConsultService.ToolStep step) {
            tools.add(step);
        }

        @Override
        public void onConfirmRequired(int step, String tool, String argsJson, String command) {
            confirms.add(command);
        }

        @Override
        public void onReasoning(String delta) {
        }

        @Override
        public void onContent(String delta) {
            contents.add(delta);
        }

        @Override
        public void onDone(String content, List<ConsultService.ToolStep> stepList) {
            doneContent = content;
            released = true;
        }

        @Override
        public void onError(String message) {
            error = message;
            released = true;
        }
    }

    private org.springframework.boot.test.web.client.TestRestTemplate http() {
        return tplHttp;
    }

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate tplHttp;

    @Test
    @Order(1)
    void 只读自动执行_黑名单拒绝_增强命令批准_全链路留痕() throws Exception {
        SCRIPT.addAll(List.of(
                toolResp(toolCall("c1", "run_diag_command",
                        "{\"instanceId\":\"pod-3\",\"command\":\"thread -n 5\"}")),
                toolResp(toolCall("c2", "run_diag_command",
                        "{\"instanceId\":\"pod-3\",\"command\":\"ognl 'x'\"}")),
                toolResp(toolCall("c3", "run_diag_command",
                        "{\"instanceId\":\"pod-3\",\"command\":\"watch demo.Foo bar '{params}' -n 5\"}")),
                textResp("结论：pod-3 线程正常。")
        ));
        Collector c = new Collector();
        c.approveCommands = List.of("watch demo.Foo bar '{params}' -n 5");
        String consultId = consult.start("tester", "pod-3", "帮我看看 pod-3", List.of(), c);
        long deadline = System.currentTimeMillis() + 15_000;
        while (c.confirms.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(c.confirms.isEmpty(), "watch 应触发审批卡");
        consult.decide(consultId, 3, true);
        deadline = System.currentTimeMillis() + 20_000;
        while (!c.released && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals("结论：pod-3 线程正常。", c.doneContent, "error=" + c.error);
        assertEquals(3, c.tools.size());
        assertEquals("auto", c.tools.get(0).decision());
        assertEquals("ok", c.tools.get(0).status());
        assertEquals("blocked", c.tools.get(1).decision());
        assertEquals("blocked", c.tools.get(1).status());
        assertEquals("approved", c.tools.get(2).decision());
        assertEquals("ok", c.tools.get(2).status());
        List<AiToolRunEntity> rows = toolRuns.findByConsultIdOrderByStepAsc(consultId);
        assertEquals(3, rows.size());
        assertEquals("auto", rows.get(0).decision);
        assertEquals("blocked", rows.get(1).decision);
        assertEquals("approved", rows.get(2).decision);
    }

    @Test
    @Order(2)
    void 审批拒绝后模型收到拒绝反馈() throws Exception {
        SCRIPT.addAll(List.of(
                toolResp(toolCall("d1", "run_diag_command",
                        "{\"instanceId\":\"pod-3\",\"command\":\"trace demo.Foo bar\"}")),
                textResp("已改用只读建议。")
        ));
        Collector c = new Collector();
        String consultId = consult.start("tester", "pod-3", "再看看", List.of(), c);
        long deadline = System.currentTimeMillis() + 15_000;
        while (c.confirms.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(c.confirms.isEmpty(), "trace 应触发审批卡");
        consult.decide(consultId, 1, false);
        deadline = System.currentTimeMillis() + 20_000;
        while (!c.released && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, c.tools.size());
        assertEquals("denied", c.tools.get(0).decision());
        assertEquals("已改用只读建议。", c.doneContent, "error=" + c.error);
        List<AiToolRunEntity> rows = toolRuns.findByConsultIdOrderByStepAsc(consultId);
        assertEquals(1, rows.size());
        assertEquals("denied", rows.get(0).decision);
    }

    @Test
    @Order(3)
    void 步数上限服务端强制() throws Exception {
        List<String> script = new ArrayList<>();
        for (int i = 0; i < ConsultService.MAX_STEPS; i++) {
            script.add(toolResp(toolCall("s" + i, "describe_instance",
                    "{\"instanceId\":\"pod-3\"}")));
        }
        script.add(textResp("收束。"));
        SCRIPT.addAll(script);
        Collector c = new Collector();
        consult.run("tester", "budget-test", "pod-3", "查到为止", List.of(), c);
        assertEquals(ConsultService.MAX_STEPS, c.tools.size());
        assertEquals("收束。", c.doneContent);
    }

    @Test
    @Order(4)
    void 未注册会话决策返回假() {
        assertFalse(consult.decide("no-such", 1, true));
        assertTrue(toolRuns.count() >= 3);
        assertNotNull(adminToken());
    }
}
