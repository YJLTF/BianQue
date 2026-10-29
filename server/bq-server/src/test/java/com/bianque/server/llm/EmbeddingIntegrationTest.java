package com.bianque.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.server.store.LlmConfigEntity;
import com.bianque.server.store.LlmConfigJpa;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * 文档向量化集成测试：JDK HttpServer 假 /embeddings 端点（关键词位向量，确定性可断言）→
 * 配置 → 重建索引 → 状态 → 向量检索命中 → 二次重建增量跳过。真实 HTTP + H2 内存库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:embedtest;MODE=MySQL")
class EmbeddingIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    EmbeddingService embeddings;

    @Autowired
    LlmConfigJpa cfgRepo;

    private static HttpServer fake;
    private static int fakePort;
    private final AtomicInteger embedCalls = new AtomicInteger();

    /** 关键词位向量：第 i 维 = 第 i 个关键词在文本中的出现次数（检索行为确定性可断言） */
    private static final String[] KEYS = {
            "thread", "memory", "jvm", "watch", "trace", "classloader", "profiler", "heap", "线程", "内存",
    };

    private static double[] vec(String text) {
        double[] v = new double[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            int idx = text.indexOf(KEYS[i]);
            while (idx >= 0) {
                v[i] += 1;
                idx = text.indexOf(KEYS[i], idx + 1);
            }
        }
        return v;
    }

    @BeforeAll
    void startFake() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/v1/embeddings", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            String req = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            // 提取 input 数组内文本（宽松解析即可：测试输入可控）
            com.fasterxml.jackson.databind.JsonNode in;
            try {
                in = new com.fasterxml.jackson.databind.ObjectMapper().readTree(req).path("input");
            } catch (Exception e) {
                in = com.fasterxml.jackson.databind.JsonNode.class.cast(null);
            }
            StringBuilder sb = new StringBuilder("{\"data\":[");
            List<String> inputs = new java.util.ArrayList<>();
            if (in != null && in.isArray()) {
                in.forEach(n -> inputs.add(n.asText()));
            }
            embedCalls.incrementAndGet();
            for (int i = 0; i < inputs.size(); i++) {
                double[] v = vec(inputs.get(i));
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"embedding\":[");
                for (int d = 0; d < v.length; d++) {
                    if (d > 0) {
                        sb.append(',');
                    }
                    sb.append(v[d]);
                }
                sb.append("]}");
            }
            sb.append("],\"model\":\"fake-embed\"}");
            byte[] out = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        fakePort = fake.getAddress().getPort();
    }

    @AfterAll
    void stopFake() {
        fake.stop(0);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + token);
        return h;
    }

    private String adminToken() {
        return String.valueOf(((Map<?, ?>) http.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class).getBody()).get("token"));
    }

    @Test
    @Order(1)
    void 未配置向量_检索为空_状态未配置() {
        cfgRepo.findById(1L).ifPresent(e -> {
            e.embedBaseUrl = null;
            e.embedModel = null;
            cfgRepo.save(e);
        });
        assertFalse(embeddings.configured());
        assertTrue(embeddings.search("thread 命令用法", 4).isEmpty());
        assertEquals(Boolean.FALSE, ((Map<?, ?>) http.exchange("/api/llm/embeddings/status", HttpMethod.GET,
                new HttpEntity<>(auth(adminToken())), Map.class).getBody()).get("configured"));
    }

    @Test
    @Order(2)
    void 配置后重建索引_状态可查() {
        LlmConfigEntity e = cfgRepo.findById(1L).orElseGet(() -> cfgRepo.save(new LlmConfigEntity()));
        e.embedBaseUrl = "http://127.0.0.1:" + fakePort + "/v1";
        e.embedApiKey = "sk-fake";
        e.embedModel = "fake-embed";
        e.embedDims = KEYS.length;
        cfgRepo.save(e);
        assertTrue(embeddings.configured());

        String token = adminToken();
        ResponseEntity<Map> r = http.exchange("/api/llm/embeddings/reindex", HttpMethod.POST,
                new HttpEntity<>(auth(token)), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        Map<?, ?> body = r.getBody();
        assertNotNull(body);
        assertTrue(((Number) body.get("pages")).intValue() >= 70, "应索引 78 页官方文档");
        assertTrue(((Number) body.get("indexed")).intValue() > 100, "分块应 > 100");

        Map<?, ?> status = http.exchange("/api/llm/embeddings/status", HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class).getBody();
        assertEquals(Boolean.TRUE, status.get("configured"));
        assertTrue(((Number) status.get("chunks")).longValue() > 100);
    }

    @Test
    @Order(3)
    void 向量检索命中相关页_二次重建增量跳过() {
        List<EmbeddingService.VecHit> hits = embeddings.search("thread 命令查看线程栈 CPU 排查", 4);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).slug().contains("thread"), "首位应命中 thread 相关页，实际 " + hits.get(0).slug());

        int callsBefore = embedCalls.get();
        String token = adminToken();
        ResponseEntity<Map> r = http.exchange("/api/llm/embeddings/reindex", HttpMethod.POST,
                new HttpEntity<>(auth(token)), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(((Number) r.getBody().get("unchangedChunks")).intValue() > 100, "内容未变应整页跳过");
        assertTrue(embedCalls.get() >= callsBefore); // 跳过路径不调用（查询页内 chunk 为空时甚至不调用）
    }

    @Test
    @Order(4)
    void 重建索引需管理员_余弦相似度语义正确() {
        String viewer = String.valueOf(((Map<?, ?>) http.postForEntity("/api/auth/login",
                Map.of("username", "viewer", "password", "viewer123"), Map.class).getBody()).get("token"));
        assertEquals(HttpStatus.FORBIDDEN, http.exchange("/api/llm/embeddings/reindex", HttpMethod.POST,
                new HttpEntity<>(Map.of(), auth(viewer)), Map.class).getStatusCode());
        double[] a = vec("thread 命令查看线程栈");
        double[] same = vec("thread 命令查看线程栈 更详细 thread 用法");
        double[] other = vec("heap dump 内存分析");
        assertTrue(EmbeddingService.cosine(a, same) > EmbeddingService.cosine(a, other));
        assertEquals(0.0, EmbeddingService.cosine(new double[]{0, 0}, new double[]{1, 1}), 1e-9);
    }
}
