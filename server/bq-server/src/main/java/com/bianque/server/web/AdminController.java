package com.bianque.server.web;

import com.bianque.server.auth.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 API（仅 ADMIN，admin:user 权限）：用户增删改查/角色/启停。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

    private final UserService users;

    public AdminController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return users.listUsers();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        return users.createUser(body.get("username"), body.get("password"),
                body.get("displayName"), body.get("role"));
    }

    @PostMapping("/{username}/password")
    public Map<String, Object> resetPassword(@PathVariable String username,
                                             @RequestBody Map<String, String> body) {
        users.resetPassword(username, body.get("password"));
        return Map.of("ok", true);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/{username}/enabled")
    public Map<String, Object> setEnabled(@PathVariable String username,
                                          @RequestBody Map<String, Object> body) {
        users.setEnabled(username, Boolean.TRUE.equals(body.get("enabled")));
        return Map.of("ok", true);
    }

    @PostMapping("/{username}/role")
    public Map<String, Object> setRole(@PathVariable String username,
                                       @RequestBody Map<String, String> body) {
        users.setRole(username, body.get("role"));
        return Map.of("ok", true);
    }
}
