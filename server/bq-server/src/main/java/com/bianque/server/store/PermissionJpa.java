package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionJpa extends JpaRepository<PermissionEntity, Long> {

    Optional<PermissionEntity> findByCode(String code);
}
