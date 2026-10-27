package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertEventJpa extends JpaRepository<AlertEventEntity, Long> {

    Optional<AlertEventEntity> findFirstByAlertIdOrderByIdDesc(String alertId);
}
