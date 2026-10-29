package com.bianque.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 模板中心集成测试：内置播种 → 诊断台列表 → 自定义模板 CRUD → 占位符 → 权限 → 体检/急诊消费，
 * 全链路走真实 HTTP + H2 内存库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:templatetest;MODE=MySQL")
class TemplateCenterIntegrationTest {

    @Autowired
    TestRestTemplate http;

    private String adminToken;
    private long customId;

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + token);
        return h;
    }

    private String login(String u, String p) {
        ResponseEntity<Map> r = http.postForEntity("/api/auth/login",
                Map.of("username", u, "password", p), Map.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return String.valueOf(((Map<?, ?>) r.getBody()).get("token"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String url, String token) {
        ResponseEntity<List> r = http.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(token)), List.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        return (List<Map<String, Object>>) r.getBody();
    }

    @Test
    @Order(1)
    void 未登录读模板_401_登录后内置已播种() {
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/templates", String.class).getStatusCode());
        adminToken = login("admin", "admin123");
        List<Map<String, Object>> tpl = list("/api/templates", adminToken);
        assertTrue(tpl.stream().anyMatch(t -> "慢接口排查".equals(t.get("name"))));
        assertTrue(String.valueOf(tpl).contains("{{类名}}"));
        assertTrue(tpl.stream().anyMatch(t -> "默认体检".equals(t.get("name"))));
        assertTrue(tpl.stream().anyMatch(t -> "默认急诊取证".equals(t.get("name"))));
    }

    @Test
    @Order(2)
    void 自定义模板创建更新停用删除全流程() {
        adminToken = login("admin", "admin123");
        Map<String, Object> req = Map.of(
                "name", "连接池水位",
                "kind", "diag",
                "scene", "自定义",
                "items", List.of(Map.of("name", "cmd", "command", "watch {{类名}} {{方法名}} '{params}' -n 50")));
        ResponseEntity<Map> created = http.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(req, auth(adminToken)), Map.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        customId = ((Number) created.getBody().get("id")).longValue();

        // 更新
        Map<String, Object> upd = Map.of(
                "name", "连接池水位 v2", "kind", "diag", "scene", "接口",
                "items", List.of(Map.of("name", "cmd", "command", "thread -n 10")));
        assertEquals(HttpStatus.OK, http.exchange("/api/templates/" + customId, HttpMethod.PUT,
                new HttpEntity<>(upd, auth(adminToken)), Map.class).getStatusCode());
        assertTrue(list("/api/templates", adminToken).stream()
                .anyMatch(t -> "连接池水位 v2".equals(t.get("name"))));

        // 停用后诊断台列表不含，管理列表含
        assertEquals(HttpStatus.OK, http.exchange("/api/templates/" + customId + "/enable", HttpMethod.POST,
                new HttpEntity<>(Map.of("enabled", false), auth(adminToken)), Map.class).getStatusCode());
        assertFalse(list("/api/templates", adminToken).stream()
                .anyMatch(t -> "连接池水位 v2".equals(t.get("name"))));
        assertTrue(list("/api/templates/all", adminToken).stream()
                .anyMatch(t -> "连接池水位 v2".equals(t.get("name"))));

        // 自定义模板可删除
        assertEquals(HttpStatus.OK, http.exchange("/api/templates/" + customId, HttpMethod.DELETE,
                new HttpEntity<>(auth(adminToken)), Map.class).getStatusCode());
    }

    @Test
    @Order(3)
    void 内置模板不可删除_但可停用() {
        adminToken = login("admin", "admin123");
        List<Map<String, Object>> all = list("/api/templates/all", adminToken);
        Number builtinId = (Number) all.stream()
                .filter(t -> "CPU 飙高".equals(t.get("name")))
                .findFirst().orElseThrow().get("id");
        ResponseEntity<Map> del = http.exchange("/api/templates/" + builtinId.longValue(), HttpMethod.DELETE,
                new HttpEntity<>(auth(adminToken)), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, del.getStatusCode());
        assertEquals(HttpStatus.OK, http.exchange("/api/templates/" + builtinId.longValue() + "/enable",
                HttpMethod.POST, new HttpEntity<>(Map.of("enabled", true), auth(adminToken)), Map.class)
                .getStatusCode());
    }

    @Test
    @Order(4)
    void viewer无写权限_体检急诊模板供消费() {
        adminToken = login("admin", "admin123");
        String viewer = login("viewer", "viewer123");
        Map<String, Object> req = Map.of("name", "x", "kind", "diag",
                "items", List.of(Map.of("name", "c", "command", "jvm")));
        assertEquals(HttpStatus.FORBIDDEN, http.exchange("/api/templates", HttpMethod.POST,
                new HttpEntity<>(req, auth(viewer)), Map.class).getStatusCode());
        // 体检模板命令集（CheckupService 消费同一口径）
        List<Map<String, Object>> checkup = list("/api/templates?kind=checkup", adminToken);
        assertTrue(checkup.stream().anyMatch(t -> "默认体检".equals(t.get("name"))));
        List<Map<String, Object>> rescue = list("/api/templates?kind=rescue", adminToken);
        assertTrue(rescue.stream().anyMatch(t -> "默认急诊取证".equals(t.get("name"))));
    }
}
