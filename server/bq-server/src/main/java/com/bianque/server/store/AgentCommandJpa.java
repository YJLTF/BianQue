package com.bianque.server.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentCommandJpa extends JpaRepository<AgentCommandEntity, Long> {

    Optional<AgentCommandEntity> findFirstByAgentIdAndStatusOrderByIdAsc(String agentId, String status);

    boolean existsByAgentIdAndStatus(String agentId, String status);

    List<AgentCommandEntity> findByStatusAndCreatedAtBefore(String status, Instant before);

    /** 原子领单：仅当仍为 PENDING 时置 RUNNING，防多心跳并发重复领取 */
    @Modifying(clearAutomatically = true)
    @Query("update AgentCommandEntity c set c.status = 'RUNNING' where c.id = :id and c.status = 'PENDING'")
    int claim(@Param("id") Long id);

    long deleteByStatusInAndCreatedAtBefore(List<String> statuses, Instant cutoff);
}
