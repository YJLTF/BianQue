package com.bianque.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证与权限拦截：/api/** 全量鉴权（登录与探活除外）。
 * 写操作（POST/PUT/DELETE）需 diag:execute；用户管理需 admin:user；只读需登录态。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR = "bianque.auth";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final TokenStore tokens;

    public AuthInterceptor(TokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        AuthContext auth = tokens.get(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"));
        String path = request.getRequestURI();
        if (path.startsWith("/api/admin/")) {
            // 管理端（用户台账 / 参数中心）读写均仅管理员
            require(auth, PermissionCodes.ADMIN_USER);
        } else if (WRITE_METHODS.contains(request.getMethod().toUpperCase())
                && !path.startsWith("/api/auth/")) {
            require(auth, PermissionCodes.DIAG_EXECUTE);
        }
        request.setAttribute(ATTR, auth);
        return true;
    }

    private void require(AuthContext auth, String permission) {
        if (!auth.hasPermission(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "权限不足（" + auth.roleName() + " 无 " + permission + "）");
        }
    }

    /** 权限点 code（与 PermissionEntity.Codes 保持一致；独立常量避免拦截器依赖 JPA 实体） */
    public static final class PermissionCodes {
        public static final String PLATFORM_READ = "platform:read";
        public static final String DIAG_EXECUTE = "diag:execute";
        public static final String ADMIN_BASELINE = "admin:baseline";
        public static final String ADMIN_USER = "admin:user";

        private PermissionCodes() {
        }
    }
}
