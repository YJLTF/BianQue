package com.bianque.server.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleJpa extends JpaRepository<UserRoleEntity, Long> {

    List<UserRoleEntity> findByUserId(long userId);
}
