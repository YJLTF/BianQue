package com.bianque.server.store;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogJpa extends JpaRepository<AuditLogEntity, Long> {

    Optional<AuditLogEntity> findBySeq(long seq);

    @Query("select coalesce(max(a.seq), 0) from AuditLogEntity a")
    long maxSeq();

    @Query("select coalesce(max(a.id), 0) from AuditLogEntity a")
    long maxId();

    /** 数据库侧分页/过滤（空串 = 不过滤），排序由 Pageable 携带 */
    @Query("""
            select a from AuditLogEntity a
            where (:source = '' or a.source = :source)
              and (:operator = '' or lower(a.operator) like lower(concat('%', :operator, '%')))
            """)
    Page<AuditLogEntity> search(@Param("source") String source,
                                @Param("operator") String operator,
                                Pageable pageable);
}
