package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 权限点（RBAC 五表之一：permissions） */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String code;
    public String name;

    public PermissionEntity() {
    }

    public PermissionEntity(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /** 权限点定义（code 语义由平台统一解释） */
    public enum Codes {
        PLATFORM_READ("platform:read", "平台只读浏览"),
        DIAG_EXECUTE("diag:execute", "执行诊断/巡检/取证/扫描"),
        ADMIN_BASELINE("admin:baseline", "下发安全基线与设置"),
        ADMIN_USER("admin:user", "用户与角色管理");

        public final String code;
        public final String label;

        Codes(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }
}
