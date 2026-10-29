package com.bianque.server.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiToolRunJpa extends JpaRepository<AiToolRunEntity, Long> {

    List<AiToolRunEntity> findByConsultIdOrderByStepAsc(String consultId);
}
