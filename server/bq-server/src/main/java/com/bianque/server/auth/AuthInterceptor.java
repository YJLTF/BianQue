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
    private final ArtifactTickets artifactTickets;

    public AuthInterceptor(TokenStore tokens, ArtifactTickets artifactTickets) {
        this.tokens = tokens;
        this.artifactTickets = artifactTickets;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        // 产物查看票据即凭据（登录后签发/60s/单工件/用后即焚）：同源 iframe 渲染火焰图专用，
        // 仅放行 GET /api/diag/artifact/{id}?bt=票据，其余请求仍走登录态
        if ("GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/api/diag/artifact/")) {
            String bt = request.getParameter("bt");
            if (bt != null && artifactTickets.consume(bt, artifactIdOf(path))) {
                return true;
            }
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        AuthContext auth = tokens.get(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"));
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

    /** /api/diag/artifact/{id} 路径中的工件 id；非纯数字段返回 -1 */
    private static long artifactIdOf(String path) {
        String seg = path.substring("/api/diag/artifact/".length());
        if (seg.isEmpty() || !seg.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        try {
            return Long.parseLong(seg);
        } catch (NumberFormatException e) {
            return -1;
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
