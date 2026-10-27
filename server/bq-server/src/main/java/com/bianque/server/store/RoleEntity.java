package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 角色（RBAC 五表之一：roles）。ADMIN=管理员 / OPERATOR=运维 / VIEWER=只读 */
@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String code;
    public String name;

    public RoleEntity() {
    }

    public RoleEntity(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
