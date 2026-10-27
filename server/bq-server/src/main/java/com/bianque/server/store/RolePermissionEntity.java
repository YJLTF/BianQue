package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 角色-权限关联（RBAC 五表之一：role_permissions） */
@Entity
@Table(name = "role_permissions")
public class RolePermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public long roleId;
    public long permissionId;

    public RolePermissionEntity() {
    }

    public RolePermissionEntity(long roleId, long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }
}
