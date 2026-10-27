package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpa extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);
}
