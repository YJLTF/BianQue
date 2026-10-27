package com.bianque.server.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证 API：登录签发 token、登出销毁、当前登录态查询。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;
    private final TokenStore tokens;

    public AuthController(UserService users, TokenStore tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        UserService.LoginOutcome o = users.login(req.username(), req.password());
        AuthContext ctx = tokens.create(o.username(), o.displayName(), o.role(), o.roleName(), o.permissions());
        return Map.of(
                "token", ctx.token(),
                "username", ctx.username(),
                "displayName", ctx.displayName(),
                "role", ctx.role(),
                "roleName", ctx.roleName(),
                "permissions", ctx.permissions(),
                "expiresAt", ctx.expiresAt().toString());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = extractBearer(authorization);
        if (token != null) {
            tokens.remove(token);
        }
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public AuthContext me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = extractBearer(authorization);
        return tokens.get(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));
    }

    private String extractBearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
