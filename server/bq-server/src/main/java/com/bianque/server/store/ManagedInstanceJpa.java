package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManagedInstanceJpa extends JpaRepository<ManagedInstanceEntity, Long> {

    Optional<ManagedInstanceEntity> findByInstanceId(String instanceId);

    void deleteByInstanceId(String instanceId);
}
