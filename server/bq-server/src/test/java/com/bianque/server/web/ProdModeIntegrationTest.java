package com.bianque.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
 * 生产模式（bianque.demo-enabled=false）集成测试：
 * 无模拟靶场实例 / 无拟真通道 / 无种子告警 / 无演示账号（ops/viewer 不存在），
 * admin 由 BIANQUE_ADMIN_PASSWORD 引导（仅首启生效）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:prodtest;MODE=MySQL",
        "bianque.demo-enabled=false",
        "bianque.admin.password=prod-admin-777",
        "bianque.agent.token=guard-prod-token"
})
class ProdModeIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectProvider<com.bianque.server.exec.MockDiagExecutor> mockExecutor;

    @Autowired
    com.bianque.server.repo.FleetRepository fleet;

    @Autowired
    com.bianque.server.alert.AlertService alerts;

    private ResponseEntity<Map> post(String url, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.setBearerAuth(token);
        }
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void 生产模式无演示内容且admin口令引导生效() {
        // 靶场实例与拟真通道均不装配
        assertTrue(fleet.allInstances().isEmpty(), "不应有模拟靶场实例");
        assertNull(mockExecutor.getIfAvailable(), "拟真通道不应装配");
        assertTrue(alerts.listAlerts().stream().noneMatch(a -> a.alertId().startsWith("AL-10")),
                "不应有种子告警");

        // 引导 admin 以配置口令登录成功；演示账号不存在
        ResponseEntity<Map> admin = post("/api/auth/login", Map.of("username", "admin", "password", "prod-admin-777"), null);
        assertEquals(HttpStatus.OK, admin.getStatusCode(), "引导 admin 应可用配置口令登录");
        assertEquals("ADMIN", admin.getBody().get("role"));
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/auth/login", Map.of("username", "ops", "password", "ops123"), null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/auth/login", Map.of("username", "viewer", "password", "viewer123"), null).getStatusCode());
    }
}
