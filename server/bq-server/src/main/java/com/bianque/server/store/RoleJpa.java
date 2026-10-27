package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleJpa extends JpaRepository<RoleEntity, Long> {

    Optional<RoleEntity> findByCode(String code);
}
