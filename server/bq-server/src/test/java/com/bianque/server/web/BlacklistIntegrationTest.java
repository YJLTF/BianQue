package com.bianque.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
 * 高危黑名单页面管理集成测试：参数中心追加 → 安全基线三份口径 → 人工诊断拦截 → 模板保存校验
 * → 清空恢复放行 → 非法/内置输入拒绝，全链路走真实 HTTP + H2 内存库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:blacklisttest;MODE=MySQL")
class BlacklistIntegrationTest {

    @Autowired
    TestRestTemplate http;

    private String adminToken;

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + adminToken);
        return h;
    }

    private void login() {
        ResponseEntity<Map> r = http.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "admin123"), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        adminToken = String.valueOf(((Map<?, ?>) r.getBody()).get("token"));
    }

    private ResponseEntity<Map> putExtra(String value) {
        return http.exchange("/api/admin/config/diag.blocked-extra-commands", HttpMethod.PUT,
                new HttpEntity<>(Map.of("value", value), auth()), Map.class);
    }

    private ResponseEntity<Map> executeJad() {
        return http.exchange("/api/diag/execute", HttpMethod.POST,
                new HttpEntity<>(Map.of("instanceId", "pod-3", "command", "jad com.demo.OrderService"), auth()),
                Map.class);
    }

    private ResponseEntity<Map> baseline() {
        return http.exchange("/api/baseline", HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
    }

    @Test
    @Order(1)
    void 追加黑名单全链路生效与恢复() {
        login();
        // 页面追加 jad（反编译类命令加严）→ 即时生效
        ResponseEntity<Map> put = putExtra("jad");
        assertEquals(HttpStatus.OK, put.getStatusCode());
        assertEquals("jad", put.getBody().get("value"));
        // 安全基线三份口径：内置基线 / 追加项 / 生效合集
        ResponseEntity<Map> base = baseline();
        assertTrue(String.valueOf(base.getBody().get("blockedCommands")).contains("ognl"));
        assertTrue(String.valueOf(base.getBody().get("blockedExtraCommands")).contains("jad"));
        String effective = String.valueOf(base.getBody().get("blockedEffectiveCommands"));
        assertTrue(effective.contains("jad") && effective.contains("heapdump"));
        // 人工诊断执行命中追加项 → 拦截（success=false，错误含命令名）
        ResponseEntity<Map> exec = executeJad();
        assertEquals(HttpStatus.OK, exec.getStatusCode());
        assertEquals(Boolean.FALSE, exec.getBody().get("success"));
        String error = String.valueOf(exec.getBody().get("error"));
        assertTrue(error.contains("已拦截") && error.contains("jad"));
        // 模板保存含黑名单命令 → fail fast 400
        Map<String, Object> tpl = Map.of(
                "name", "反编译体检", "kind", "diag", "scene", "自定义",
                "items", List.of(Map.of("name", "cmd", "command", "jad com.demo.OrderService")));
        ResponseEntity<Map> tplRes = http.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(tpl, auth()), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, tplRes.getStatusCode());
        assertTrue(String.valueOf(tplRes.getBody()).contains("黑名单"));
        // 清空追加项 → 该命令恢复放行（拟真通道）
        assertEquals(HttpStatus.OK, putExtra("").getStatusCode());
        ResponseEntity<Map> exec2 = executeJad();
        assertEquals(HttpStatus.OK, exec2.getStatusCode());
        assertFalse(Boolean.FALSE.equals(exec2.getBody().get("success")));
    }

    @Test
    @Order(2)
    void 内置基线不可追加_非法token被拒绝() {
        login();
        // 内置基线命令拒绝重复登记（恒拦截，无需追加）
        ResponseEntity<Map> r1 = putExtra("ognl");
        assertEquals(HttpStatus.BAD_REQUEST, r1.getStatusCode());
        // 非法 token（数字开头 / 含路径分隔）拒绝且不落值
        assertEquals(HttpStatus.BAD_REQUEST, putExtra("watch 1x").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, putExtra("cat /etc/passwd").getStatusCode());
        ResponseEntity<Map> base = baseline();
        assertTrue(String.valueOf(base.getBody().get("blockedExtraCommands")).isEmpty()
                || "[]".equals(String.valueOf(base.getBody().get("blockedExtraCommands"))));
        // 基线命令依然恒拦截（不可经任何配置削减）
        ResponseEntity<Map> exec = http.exchange("/api/diag/execute", HttpMethod.POST,
                new HttpEntity<>(Map.of("instanceId", "pod-3", "command", "ognl '@x'"), auth()), Map.class);
        assertEquals(Boolean.FALSE, exec.getBody().get("success"));
    }
}
