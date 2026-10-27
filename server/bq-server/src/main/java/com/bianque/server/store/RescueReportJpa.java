package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RescueReportJpa extends JpaRepository<RescueReportEntity, Long> {

    Optional<RescueReportEntity> findFirstByAlertIdOrderByIdDesc(String alertId);
}
