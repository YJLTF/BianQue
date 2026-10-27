package com.bianque.server.auth;

import java.time.Instant;
import java.util.Set;

/** 登录态：由 UserService.login 签发，拦截器校验后挂到请求属性 */
public record AuthContext(
        String token,
        String username,
        String displayName,
        String role,
        String roleName,
        Set<String> permissions,
        Instant expiresAt) {

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }
}
