package com.bianque.server.store;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagArtifactJpa extends JpaRepository<DiagArtifactEntity, Long> {

    long deleteByCreatedAtBefore(java.time.Instant cutoff);
}
