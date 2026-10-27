package com.bianque.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * S1 集成测试：登录 → 角色越权 → 审计哈希链，全链路走真实 HTTP + H2 内存库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:authtest;MODE=MySQL",
        "bianque.session.ttl-minutes=30"
})
class AuthFlowIntegrationTest {

    @Autowired
    TestRestTemplate http;

    private ResponseEntity<Map> post(String url, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<Map> get(String url, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String login(String user, String pass) {
        ResponseEntity<Map> r = post("/api/auth/login", Map.of("username", user, "password", pass), null);
        assertEquals(HttpStatus.OK, r.getStatusCode(), "登录应成功: " + user);
        return (String) r.getBody().get("token");
    }

    @Test
    @Order(1)
    void 正确口令登录成功且含角色权限() {
        ResponseEntity<Map> r = post("/api/auth/login", Map.of("username", "admin", "password", "admin123"), null);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("ADMIN", r.getBody().get("role"));
        assertTrue(((List<String>) r.getBody().get("permissions")).contains("admin:user"));
    }

    @Test
    @Order(2)
    void 错误口令返回400() {
        ResponseEntity<Map> r = post("/api/auth/login", Map.of("username", "admin", "password", "wrong"), null);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test
    @Order(3)
    void 未登录访问业务API返回401() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/fleet/overview", null).getStatusCode());
    }

    @Test
    @Order(4)
    void 只读角色执行诊断被拒403() {
        String viewer = login("viewer", "viewer123");
        assertEquals(HttpStatus.FORBIDDEN,
                post("/api/diag/execute", Map.of("instanceId", "pod-3", "command", "thread -n 3"), viewer)
                        .getStatusCode());
    }

    @Test
    @Order(5)
    void 管理员执行诊断并写入审计含真实操作人() {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> r = post("/api/diag/execute",
                Map.of("instanceId", "pod-3", "command", "thread -n 3"), admin);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(Boolean.TRUE, r.getBody().get("success"));

        List<Map> entries = http.exchange("/api/audit?limit=10", HttpMethod.GET,
                new HttpEntity<>(auth(admin)), List.class).getBody();
        assertFalse(entries == null || entries.isEmpty());
        assertTrue(String.valueOf(entries.get(0).get("operator")).startsWith("admin"));
        assertNotEquals("", entries.get(0).get("hash"));
    }

    @Test
    @Order(6)
    void 审计哈希链校验通过() {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> v = get("/api/audit/verify", admin);
        assertEquals(HttpStatus.OK, v.getStatusCode());
        assertEquals(Boolean.TRUE, v.getBody().get("valid"));
        assertNotEquals(0, ((Number) v.getBody().get("checked")).longValue());
    }

    @Test
    @Order(7)
    void 用户管理仅管理员可见() {
        String admin = login("admin", "admin123");
        String viewer = login("viewer", "viewer123");
        List<Map> users = http.exchange("/api/admin/users", HttpMethod.GET,
                new HttpEntity<>(auth(admin)), List.class).getBody();
        assertFalse(users == null || users.isEmpty());
        assertEquals(HttpStatus.FORBIDDEN, http.exchange("/api/admin/users", HttpMethod.GET,
                new HttpEntity<>(auth(viewer)), String.class).getStatusCode());
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @Order(8)
    void 篡改审计记录后哈希链断链可检() {
        String admin = login("admin", "admin123");
        jdbc.update("update audit_log set command = concat(command, '_篡改') where seq = 1");
        ResponseEntity<Map> v = get("/api/audit/verify", admin);
        assertEquals(HttpStatus.OK, v.getStatusCode());
        assertEquals(Boolean.FALSE, v.getBody().get("valid"), "篡改后链校验必须失败");
        assertEquals(1, ((Number) v.getBody().get("brokenAtSeq")).intValue());
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
