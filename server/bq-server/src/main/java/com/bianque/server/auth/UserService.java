package com.bianque.server.auth;

import com.bianque.core.util.HashUtil;
import com.bianque.server.store.PermissionEntity;
import com.bianque.server.store.RoleEntity;
import com.bianque.server.store.RolePermissionEntity;
import com.bianque.server.store.RolePermissionJpa;
import com.bianque.server.store.UserEntity;
import com.bianque.server.store.UserJpa;
import com.bianque.server.store.UserRoleEntity;
import com.bianque.server.store.UserRoleJpa;
import com.bianque.server.store.RoleJpa;
import com.bianque.server.store.PermissionJpa;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户与 RBAC：五表模型（users/roles/permissions/user_roles/role_permissions），
 * 首次启动种子三个演示账号；口令 sha256(salt+password)，S2 可升级 BCrypt/OIDC。
 */
@Service
public class UserService {

    private final UserJpa users;
    private final RoleJpa roles;
    private final PermissionJpa permissions;
    private final UserRoleJpa userRoles;
    private final RolePermissionJpa rolePermissions;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserJpa users, RoleJpa roles, PermissionJpa permissions,
                       UserRoleJpa userRoles, RolePermissionJpa rolePermissions) {
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
        this.userRoles = userRoles;
        this.rolePermissions = rolePermissions;
    }

    /** 角色权限矩阵：ADMIN 全量 / OPERATOR 可执行 / VIEWER 只读 */
    private static final Map<String, List<PermissionEntity.Codes>> ROLE_PERMS = Map.of(
            "ADMIN", List.of(PermissionEntity.Codes.values()),
            "OPERATOR", List.of(PermissionEntity.Codes.PLATFORM_READ, PermissionEntity.Codes.DIAG_EXECUTE),
            "VIEWER", List.of(PermissionEntity.Codes.PLATFORM_READ));

    private static final Map<String, String> ROLE_NAMES = Map.of(
            "ADMIN", "管理员", "OPERATOR", "运维", "VIEWER", "只读");

    @PostConstruct
    @Transactional
    public void seedIfEmpty() {
        Map<String, RoleEntity> roleByCode = new LinkedHashMap<>();
        for (String code : ROLE_NAMES.keySet()) {
            RoleEntity r = roles.findByCode(code).orElseGet(() -> roles.save(new RoleEntity(code, ROLE_NAMES.get(code))));
            roleByCode.put(code, r);
        }
        Map<String, PermissionEntity> permByCode = new LinkedHashMap<>();
        for (PermissionEntity.Codes p : PermissionEntity.Codes.values()) {
            PermissionEntity e = permissions.findByCode(p.code)
                    .orElseGet(() -> permissions.save(new PermissionEntity(p.code, p.label)));
            permByCode.put(p.code, e);
        }
        for (var roleEntry : ROLE_PERMS.entrySet()) {
            RoleEntity r = roleByCode.get(roleEntry.getKey());
            Set<Long> linked = new HashSet<>();
            rolePermissions.findByRoleId(r.id).forEach(rp -> linked.add(rp.permissionId));
            for (PermissionEntity.Codes c : roleEntry.getValue()) {
                PermissionEntity p = permByCode.get(c.code);
                if (!linked.contains(p.id)) {
                    rolePermissions.save(new RolePermissionEntity(r.id, p.id));
                }
            }
        }
        seedUser("admin", "admin123", "管理员(演示)", "ADMIN");
        seedUser("ops", "ops123", "运维(演示)", "OPERATOR");
        seedUser("viewer", "viewer123", "访客(只读)", "VIEWER");
    }

    private void seedUser(String username, String password, String displayName, String roleCode) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        String salt = newSalt();
        UserEntity u = new UserEntity(username, HashUtil.sha256Hex(salt + password), salt, displayName);
        users.save(u);
        RoleEntity r = roles.findByCode(roleCode).orElseThrow();
        userRoles.save(new UserRoleEntity(u.id, r.id));
    }

    private String newSalt() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public LoginOutcome login(String username, String password) {
        // 口令错误返回 400（而非 401）：与"未登录"语义区分，也避开流式请求体客户端对 401 的重试缺陷
        UserEntity u = users.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码错误"));
        if (!u.enabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用");
        }
        if (!HashUtil.sha256Hex(u.salt + password).equals(u.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码错误");
        }
        List<RoleEntity> rs = userRoles.findByUserId(u.id).stream()
                .map(ur -> roles.findById(ur.roleId).orElse(null))
                .filter(r -> r != null)
                .toList();
        String roleCode = rs.isEmpty() ? "VIEWER" : rs.get(0).code;
        Set<String> perms = new HashSet<>();
        for (RoleEntity r : rs) {
            rolePermissions.findByRoleId(r.id).stream()
                    .map(rp -> permissions.findById(rp.permissionId).orElse(null))
                    .filter(p -> p != null)
                    .forEach(p -> perms.add(p.code));
        }
        return new LoginOutcome(u.username, u.displayName, roleCode,
                ROLE_NAMES.getOrDefault(roleCode, roleCode), perms);
    }

    public record LoginOutcome(String username, String displayName, String role,
                               String roleName, Set<String> permissions) {
    }

    /** 管理端用户列表（含角色与权限） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers() {
        return users.findAll().stream()
                .map(u -> {
                    List<String> roleCodes = userRoles.findByUserId(u.id).stream()
                            .map(ur -> roles.findById(ur.roleId).orElse(null))
                            .filter(r -> r != null)
                            .map(r -> r.code)
                            .toList();
                    Set<String> perms = new HashSet<>();
                    roleCodes.forEach(code -> roles.findByCode(code).ifPresent(r ->
                            rolePermissions.findByRoleId(r.id).forEach(rp ->
                                    permissions.findById(rp.permissionId)
                                            .ifPresent(p -> perms.add(p.code)))));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", u.username);
                    m.put("displayName", u.displayName);
                    m.put("enabled", u.enabled);
                    m.put("roles", roleCodes);
                    m.put("permissions", perms);
                    return m;
                })
                .toList();
    }
}
