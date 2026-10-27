package com.bianque.server.store;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 用户（RBAC 五表之一：users） */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String username;
    public String passwordHash; // sha256(salt + password)，S2 可升级 BCrypt
    public String salt;
    public String displayName;
    public boolean enabled = true;

    public UserEntity() {
    }

    public UserEntity(String username, String passwordHash, String salt, String displayName) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.displayName = displayName;
    }
}
