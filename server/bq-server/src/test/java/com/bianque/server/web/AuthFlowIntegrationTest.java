package com.bianque.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.core.util.HashUtil;
import com.bianque.server.store.RoleEntity;
import com.bianque.server.store.RoleJpa;
import com.bianque.server.store.UserEntity;
import com.bianque.server.store.UserJpa;
import com.bianque.server.store.UserRoleEntity;
import com.bianque.server.store.UserRoleJpa;
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

    @Autowired
    UserJpa users;

    @Autowired
    RoleJpa roles;

    @Autowired
    UserRoleJpa userRoles;

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

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.exchange("/api/audit?page=0&size=10", HttpMethod.GET,
                new HttpEntity<>(auth(admin)), Map.class).getBody();
        List<Map> entries = resp == null ? null : (List<Map>) resp.get("records");
        assertFalse(entries == null || entries.isEmpty());
        assertTrue(((Number) resp.get("total")).longValue() >= entries.size());
        assertTrue(String.valueOf(entries.get(0).get("operator")).startsWith("admin"));
        assertNotEquals("", entries.get(0).get("hash"));
    }

    @Test
    @Order(6)
    void 审计分页返回总数且seq倒序无重叠() {
        String admin = login("admin", "admin123");
        // 自播种 5 条，不依赖其它用例的写入量
        com.bianque.server.repo.AuditRepository repo = auditRepo.getObject();
        for (int i = 1; i <= 5; i++) {
            repo.append("系统", "pager-test", "pod-3", "seed " + i, i, "ok");
        }
        Map<String, Object> p0 = http.exchange("/api/audit?page=0&size=2", HttpMethod.GET,
                new HttpEntity<>(auth(admin)), Map.class).getBody();
        Map<String, Object> p1 = http.exchange("/api/audit?page=1&size=2", HttpMethod.GET,
                new HttpEntity<>(auth(admin)), Map.class).getBody();
        List<Map> r0 = (List<Map>) p0.get("records");
        List<Map> r1 = (List<Map>) p1.get("records");
        assertEquals(2, r0.size());
        assertEquals(2, r1.size());
        long total = ((Number) p0.get("total")).longValue();
        assertTrue(total >= 5);
        assertEquals(total, ((Number) p1.get("total")).longValue());
        long firstSeq0 = ((Number) r0.get(0).get("seq")).longValue();
        long firstSeq1 = ((Number) r1.get(0).get("seq")).longValue();
        assertTrue(firstSeq0 > firstSeq1, "seq 应倒序：第 1 页 seq 大于第 2 页");
    }

    @Test
    @Order(7)
    void 审计哈希链校验通过() {
        String admin = login("admin", "admin123");
        ResponseEntity<Map> v = get("/api/audit/verify", admin);
        assertEquals(HttpStatus.OK, v.getStatusCode());
        assertEquals(Boolean.TRUE, v.getBody().get("valid"));
        assertNotEquals(0, ((Number) v.getBody().get("checked")).longValue());
    }

    @Test
    @Order(8)
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

    @Autowired
    org.springframework.beans.factory.ObjectProvider<com.bianque.server.repo.AuditRepository> auditRepo;

    @Test
    @Order(9)
    void 用户管理全流程_创建改角停用与admin保护() {
        String admin = login("admin", "admin123");
        assertEquals(HttpStatus.OK, post("/api/admin/users", Map.of(
                "username", "tester01", "password", "tester123", "displayName", "测试工", "role", "OPERATOR"),
                admin).getStatusCode());
        // 重复用户名 → 409
        assertEquals(HttpStatus.CONFLICT, post("/api/admin/users", Map.of(
                "username", "tester01", "password", "tester123", "role", "VIEWER"), admin).getStatusCode());
        // 新用户可登录
        String tester = login("tester01", "tester123");
        assertNotNull(tester);
        // 改为 VIEWER 后重置密码，旧/新密码均验证
        assertEquals(HttpStatus.OK, post("/api/admin/users/tester01/role",
                Map.of("role", "VIEWER"), admin).getStatusCode());
        assertEquals(HttpStatus.OK, post("/api/admin/users/tester01/password",
                Map.of("password", "newpass456"), admin).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/auth/login",
                Map.of("username", "tester01", "password", "tester123"), null).getStatusCode());
        assertNotNull(login("tester01", "newpass456"));
        // 停用后登录被拒（403 账号已停用）
        assertEquals(HttpStatus.OK, post("/api/admin/users/tester01/enabled",
                Map.of("enabled", false), admin).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, post("/api/auth/login",
                Map.of("username", "tester01", "password", "newpass456"), null).getStatusCode());
        // 内置 admin 不允许停用/改角色（防误降级锁死平台）
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/admin/users/admin/enabled",
                Map.of("enabled", false), admin).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/admin/users/admin/role",
                Map.of("role", "OPERATOR"), admin).getStatusCode());
        // 非 admin 无权管理用户
        assertEquals(HttpStatus.FORBIDDEN, post("/api/admin/users", Map.of(
                "username", "tester02", "password", "tester123", "role", "VIEWER"), tester).getStatusCode());
    }

    @Test
    @Order(10)
    void 篡改审计记录后哈希链断链可检() {
        String admin = login("admin", "admin123");
        jdbc.update("update audit_log set command = concat(command, '_篡改') where seq = 1");
        ResponseEntity<Map> v = get("/api/audit/verify", admin);
        assertEquals(HttpStatus.OK, v.getStatusCode());
        assertEquals(Boolean.FALSE, v.getBody().get("valid"), "篡改后链校验必须失败");
        assertEquals(1, ((Number) v.getBody().get("brokenAtSeq")).intValue());
    }

    @Test
    @Order(11)
    void 并发审计追加不重号且链完整() throws Exception {
        // 先还原 Order(8) 的篡改，保证全链可校验
        jdbc.update("update audit_log set command = replace(command, '_篡改', '') where command like '%\\_篡改'");
        com.bianque.server.repo.AuditRepository repo = auditRepo.getObject();
        int threads = 8, per = 12;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < per; i++) {
                        repo.append("并发测试", "tester-" + id, "pod-1", "cmd-" + id + "-" + i, 1, "ok");
                    }
                }));
            }
            for (var f : fs) {
                f.get();
            }
        }
        // 序号无重复：总数 = max(seq)
        Number maxSeq = jdbc.queryForObject("select max(seq) from audit_log", Number.class);
        Number rows = jdbc.queryForObject("select count(*) from audit_log", Number.class);
        assertEquals(maxSeq.longValue(), rows.longValue(), "seq 存在重复/空洞");
        // 哈希链完整
        Map<String, Object> v = repo.verifyChain();
        assertEquals(Boolean.TRUE, v.get("valid"), "并发追加后链必须完整: " + v);
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    @Order(12)
    void 显示名修改_本人可改_改他人仅管理员() {
        String viewer = login("viewer", "viewer123");
        // 本人改显示名：即时生效（/me 可见）
        ResponseEntity<Map> put = http.exchange("/api/auth/profile", HttpMethod.PUT,
                new HttpEntity<>(Map.of("displayName", "访客张三"), auth(viewer)), Map.class);
        assertEquals(HttpStatus.OK, put.getStatusCode(), "本人应可改显示名: " + put.getBody());
        ResponseEntity<Map> me = get("/api/auth/me", viewer);
        assertEquals("访客张三", me.getBody().get("displayName"));

        // viewer 改他人 → 403（/api/admin/users 仅管理员）
        assertEquals(HttpStatus.FORBIDDEN, http.exchange("/api/admin/users/ops/display-name", HttpMethod.POST,
                new HttpEntity<>(Map.of("displayName", "冒充"), auth(viewer)), Map.class).getStatusCode());

        // 管理员改他人 → 生效；非法值 → 400
        String admin = login("admin", "admin123");
        assertEquals(HttpStatus.OK, http.exchange("/api/admin/users/viewer/display-name", HttpMethod.POST,
                new HttpEntity<>(Map.of("displayName", "访客改名"), auth(admin)), Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange("/api/admin/users/viewer/display-name", HttpMethod.POST,
                new HttpEntity<>(Map.of("displayName", "  "), auth(admin)), Map.class).getStatusCode());
        List<?> users = (List<?>) http.exchange("/api/admin/users", HttpMethod.GET,
                new HttpEntity<>(auth(admin)), List.class).getBody();
        assertTrue(users.stream().anyMatch(u -> "访客改名".equals(((Map<?, ?>) u).get("displayName"))));
    }

    @Test
    @Order(13)
    void 用户删除_保护规则与闭环() {
        String admin = login("admin", "admin123");
        // 内置 admin 不可删；viewer 删除 → 403
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange("/api/admin/users/admin", HttpMethod.DELETE,
                new HttpEntity<>(auth(admin)), Map.class).getStatusCode());
        String viewer = login("viewer", "viewer123");
        assertEquals(HttpStatus.FORBIDDEN, http.exchange("/api/admin/users/ops", HttpMethod.DELETE,
                new HttpEntity<>(auth(viewer)), Map.class).getStatusCode());
        // 创建 → 删除 → 登录失效 → 重复删除 404
        assertEquals(HttpStatus.OK, post("/api/admin/users",
                Map.of("username", "tmpdel", "password", "tmp123", "displayName", "临时", "role", "VIEWER"), admin)
                .getStatusCode());
        assertEquals(HttpStatus.OK, http.exchange("/api/admin/users/tmpdel", HttpMethod.DELETE,
                new HttpEntity<>(auth(admin)), Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/auth/login",
                Map.of("username", "tmpdel", "password", "tmp123"), null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.exchange("/api/admin/users/tmpdel", HttpMethod.DELETE,
                new HttpEntity<>(auth(admin)), Map.class).getStatusCode());
    }

    @Test
    @Order(14)
    void 历史sha256口令行登录校验通过后透明升级BCrypt() {
        // 直接种一条历史形态的 sha256(salt+password) 用户
        String username = "legacy0001";
        users.findByUsername(username).ifPresent(u -> users.delete(u));
        UserEntity legacy = new UserEntity(username,
                HashUtil.sha256Hex("old-salt-16bytes!" + "legacy-pw-777"), "old-salt-16bytes!", "历史口令");
        users.save(legacy);
        RoleEntity viewer = roles.findByCode("VIEWER").orElseThrow();
        userRoles.save(new UserRoleEntity(legacy.id, viewer.id));

        // 登录成功（走遗留 sha256 分支）并升级
        ResponseEntity<Map> r = post("/api/auth/login",
                Map.of("username", username, "password", "legacy-pw-777"), null);
        assertEquals(HttpStatus.OK, r.getStatusCode(), "历史 sha256 行应能登录");

        // 库内已升级为 BCrypt，salt 清空；再次登录走 BCrypt 分支仍成功
        UserEntity after = users.findByUsername(username).orElseThrow();
        assertTrue(after.passwordHash.startsWith("$2"), "登录后应透明升级为 BCrypt: " + after.passwordHash);
        assertEquals("", after.salt);
        assertEquals(HttpStatus.OK, post("/api/auth/login",
                Map.of("username", username, "password", "legacy-pw-777"), null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post("/api/auth/login",
                Map.of("username", username, "password", "wrong-pw"), null).getStatusCode());
    }
}
