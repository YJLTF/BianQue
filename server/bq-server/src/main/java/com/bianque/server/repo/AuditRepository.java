package com.bianque.server.repo;

import com.bianque.server.model.Types.AuditEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/**
 * 审计仓储：追加写环形缓冲（上限 5000 条）。S1 起落 MySQL 分区表 + 哈希链。
 */
@Repository
public class AuditRepository {

    private static final int CAP = 5000;

    private final Deque<AuditEntry> entries = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public AuditEntry append(String source, String operator, String instanceId,
                             String command, long costMs, String result) {
        AuditEntry e = new AuditEntry(seq.incrementAndGet(), Instant.now(),
                source, operator, instanceId, command, costMs, result);
        entries.addFirst(e);
        while (entries.size() > CAP) {
            entries.pollLast();
        }
        return e;
    }

    /** 倒序查询，支持来源与操作人过滤（简易版） */
    public List<AuditEntry> query(String source, String operatorLike, int limit) {
        List<AuditEntry> all = new ArrayList<>(entries);
        all.sort(Comparator.comparing(AuditEntry::seq).reversed());
        return all.stream()
                .filter(e -> source == null || source.isBlank() || e.source().equals(source))
                .filter(e -> operatorLike == null || operatorLike.isBlank()
                        || e.operator().toLowerCase().contains(operatorLike.toLowerCase()))
                .limit(limit <= 0 ? 50 : Math.min(limit, 500))
                .toList();
    }

    public long count() {
        return seq.get();
    }

    public Map<String, Object> stats() {
        return Map.of("appended", seq.get(), "inMemory", entries.size());
    }
}
