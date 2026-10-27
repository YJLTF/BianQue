package com.bianque.server.store;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckupRunJpa extends JpaRepository<CheckupRunEntity, Long> {

    Optional<CheckupRunEntity> findFirstByOrderByIdDesc();

    List<CheckupRunEntity> findTop20ByOrderByIdDesc();
}
