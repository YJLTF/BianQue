package com.bianque.server.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagTemplateJpa extends JpaRepository<DiagTemplateEntity, Long> {

    List<DiagTemplateEntity> findByKindOrderBySortAscIdAsc(String kind);

    List<DiagTemplateEntity> findByKindAndEnabledTrueOrderBySortAscIdAsc(String kind);
}
