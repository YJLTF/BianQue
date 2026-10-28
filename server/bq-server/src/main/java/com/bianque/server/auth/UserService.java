package com.bianque.server.auth;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户与 RBAC：五表模型（users/roles/permissions/user_roles/role_permissions），
 * 口令 BCrypt 存储；历史 sha256(salt+password) 行在登录校验通过后透明升级为 BCrypt。
 * 演示模式（bianque.demo-enabled=true）首次启动种子 admin/ops/viewer 三个演示账号；
 * 生产模式（false）只引导 admin，初始口令必须由 BIANQUE_ADMIN_PASSWORD 提供（缺省拒绝启动防锁死）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserJpa users;
    private final RoleJpa roles;
    private final PermissionJpa permissions;
    private final UserRoleJpa userRoles;
    private final RolePermissionJpa rolePermissions;
    private final boolean demoEnabled;
    private final String bootAdminPassword;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    public UserService(UserJpa users, RoleJpa roles, PermissionJpa permissions,
                       UserRoleJpa userRoles, RolePermissionJpa rolePermissions,
                       @Value("${bianque.demo-enabled:true}") boolean demoEnabled,
                       @Value("${bianque.admin.password:}") String bootAdminPassword) {
        this.users = users;
        this.roles = roles;
        this.permissions = permissions;
        this.userRoles = userRoles;
        this.rolePermissions = rolePermissions;
        this.demoEnabled = demoEnabled;
        this.bootAdminPassword = bootAdminPassword == null ? "" : bootAdminPassword;
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
        if (demoEnabled) {
            seedUser("admin", "admin123", "管理员(演示)", "ADMIN");
            seedUser("ops", "ops123", "运维(演示)", "OPERATOR");
            seedUser("viewer", "viewer123", "访客(只读)", "VIEWER");
        } else {
            if (users.findByUsername("admin").isEmpty()) {
                if (bootAdminPassword.isBlank()) {
                    throw new IllegalStateException("演示模式已关闭（bianque.demo-enabled=false）："
                            + "首次启动必须通过 BIANQUE_ADMIN_PASSWORD 设置 admin 初始口令");
                }
                seedUser("admin", bootAdminPassword, "管理员", "ADMIN");
            }
            for (String legacy : List.of("ops", "viewer")) {
                users.findByUsername(legacy).ifPresent(u ->
                        log.warn("历史演示账号「{}」仍存在，生产环境请在「平台管理 → 用户与角色」中删除", legacy));
            }
        }
    }

    private void seedUser(String username, String password, String displayName, String roleCode) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        UserEntity u = new UserEntity(username, encoder.encode(password), "", displayName);
        users.save(u);
        RoleEntity r = roles.findByCode(roleCode).orElseThrow();
        userRoles.save(new UserRoleEntity(u.id, r.id));
    }

    @Transactional
    public LoginOutcome login(String username, String password) {
        // 口令错误返回 400（而非 401）：与"未登录"语义区分，也避开流式请求体客户端对 401 的重试缺陷
        UserEntity u = users.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码错误"));
        if (!u.enabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用");
        }
        if (u.passwordHash != null && u.passwordHash.startsWith("$2")) {
            // BCrypt（当前形态）
            if (!encoder.matches(password, u.passwordHash)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码错误");
            }
        } else {
            // 历史 sha256(salt+password) 行：校验通过后透明升级为 BCrypt（下次登录直接走 BCrypt）
            if (!com.bianque.core.util.HashUtil.sha256Hex(u.salt + password).equals(u.passwordHash)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名或密码错误");
            }
            u.salt = "";
            u.passwordHash = encoder.encode(password);
            users.save(u);
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

    // ---------- 用户管理（仅 admin:user） ----------

    /** 创建用户并绑定单一角色 */
    @Transactional
    public Map<String, Object> createUser(String username, String password, String displayName, String roleCode) {
        String un = username == null ? "" : username.trim();
        if (!un.matches("[a-zA-Z0-9_-]{2,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名仅限字母/数字/_/-，2–32 位");
        }
        checkPassword(password);
        if (users.findByUsername(un).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        RoleEntity r = requireRole(roleCode);
        UserEntity u = users.save(new UserEntity(un, encoder.encode(password), "",
                displayName == null || displayName.isBlank() ? un : displayName.trim()));
        userRoles.save(new UserRoleEntity(u.id, r.id));
        return Map.of("username", u.username, "displayName", u.displayName,
                "enabled", u.enabled, "roles", List.of(r.code));
    }

    /** 重置口令 */
    @Transactional
    public void resetPassword(String username, String newPassword) {
        UserEntity u = requireUser(username);
        checkPassword(newPassword);
        u.salt = "";
        u.passwordHash = encoder.encode(newPassword);
        users.save(u);
    }

    /** 启用/停用（内置 admin 不允许停用，防止锁死平台） */
    @Transactional
    public void setEnabled(String username, boolean enabled) {
        UserEntity u = requireUser(username);
        if ("admin".equals(u.username) && !enabled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置 admin 账号不允许停用");
        }
        u.enabled = enabled;
        users.save(u);
    }

    /** 调整角色（单角色模型；内置 admin 角色锁定，防止误降级锁死平台） */
    @Transactional
    public void setRole(String username, String roleCode) {
        UserEntity u = requireUser(username);
        if ("admin".equals(u.username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置 admin 账号角色不可变更");
        }
        RoleEntity r = requireRole(roleCode);
        userRoles.findByUserId(u.id).forEach(userRoles::delete);
        userRoles.save(new UserRoleEntity(u.id, r.id));
    }

    /** 修改显示名（自己随时可改；管理员改他人走 /api/admin/users 通道） */
    @Transactional
    public Map<String, Object> updateDisplayName(String targetUsername, String displayName) {
        String dn = displayName == null ? "" : displayName.trim();
        if (dn.isEmpty() || dn.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "显示名需 1–32 个字符");
        }
        UserEntity u = requireUser(targetUsername);
        u.displayName = dn;
        users.save(u);
        return Map.of("username", u.username, "displayName", u.displayName);
    }

    /** 删除用户（内置 admin 与当前登录账号不可删）；用于摘除历史演示账号等场景 */
    @Transactional
    public void deleteUser(String targetUsername, String operator) {
        UserEntity u = requireUser(targetUsername);
        if ("admin".equals(u.username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置 admin 账号不允许删除");
        }
        if (u.username.equals(operator)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除当前登录账号");
        }
        userRoles.findByUserId(u.id).forEach(userRoles::delete);
        users.delete(u);
    }

    private UserEntity requireUser(String username) {
        return users.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private RoleEntity requireRole(String roleCode) {
        return roles.findByCode(roleCode == null ? "" : roleCode.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色无效（可选 ADMIN/OPERATOR/VIEWER）"));
    }

    private void checkPassword(String password) {
        if (password == null || password.length() < 6) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 6 位");
        }
    }
}
