package com.bianque.server.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RolePermissionJpa extends JpaRepository<RolePermissionEntity, Long> {

    List<RolePermissionEntity> findByRoleId(long roleId);
}
