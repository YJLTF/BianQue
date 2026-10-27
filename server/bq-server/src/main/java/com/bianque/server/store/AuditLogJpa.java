package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditLogJpa extends JpaRepository<AuditLogEntity, Long> {

    Optional<AuditLogEntity> findBySeq(long seq);

    @Query("select coalesce(max(a.seq), 0) from AuditLogEntity a")
    long maxSeq();

    @Query("select coalesce(max(a.id), 0) from AuditLogEntity a")
    long maxId();
}
