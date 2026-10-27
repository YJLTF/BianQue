package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 用户-角色关联（RBAC 五表之一：user_roles） */
@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public long userId;
    public long roleId;

    public UserRoleEntity() {
    }

    public UserRoleEntity(long userId, long roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }
}
