package com.bianque.server.web;

import com.bianque.server.auth.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 API（仅 ADMIN）：用户/角色/权限台账。
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
}
