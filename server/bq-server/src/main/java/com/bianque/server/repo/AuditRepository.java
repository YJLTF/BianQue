package com.bianque.server.repo;

import com.bianque.core.util.HashUtil;
import com.bianque.server.model.Types.AuditEntry;
import com.bianque.server.store.AuditLogEntity;
import com.bianque.server.store.AuditLogJpa;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计仓储（S1 落库版）：追加写 + 哈希链。
 * hash = sha256(seq|time|source|operator|instance|command|cost|result|prevHash)，
 * 首条 prevHash 为全零创世值；任何一条被篡改，其后所有条目断链可检。
 */
@Repository
public class AuditRepository implements AuditTrail {

    static final String GENESIS = "0".repeat(64);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final int QUERY_SCAN_CAP = 500;

    private final AuditLogJpa jpa;
    private final ObjectProvider<AuditRepository> self; // 自代理：让事务提交发生在 appendLock 临界区内
    private final Object appendLock = new Object();

    public AuditRepository(AuditLogJpa jpa, ObjectProvider<AuditRepository> self) {
        this.jpa = jpa;
        this.self = self;
    }

    /**
     * 并发安全的关键：seq 生成（maxSeq+1）必须与提交串行化。
     * 若锁在 @Transactional 代理内层，提交发生在锁外，并发线程会读到相同的 maxSeq 导致重复键。
     * 因此锁包住"整个事务方法"（经自身代理调用），提交完成才放行下一个线程。
     */
    @Override
    public AuditEntry append(String source, String operator, String instanceId,
                             String command, long costMs, String result) {
        synchronized (appendLock) {
            return self.getObject().appendInTransaction(source, operator, instanceId, command, costMs, result);
        }
    }

    @Transactional
    public AuditEntry appendInTransaction(String source, String operator, String instanceId,
                                          String command, long costMs, String result) {
        long seq = jpa.maxSeq() + 1;
        String prevHash = seq == 1
                ? GENESIS
                : jpa.findBySeq(seq - 1).map(e -> e.hash).orElse(GENESIS);
        Instant now = Instant.now();
        String timeText = TS.format(now);
        String hash = HashUtil.sha256Hex(seq + "|" + timeText + "|" + source + "|" + operator
                + "|" + instanceId + "|" + command + "|" + costMs + "|" + result + "|" + prevHash);
        AuditLogEntity e = new AuditLogEntity(seq, now, timeText, source, operator, instanceId,
                command, costMs, result, prevHash, hash);
        jpa.saveAndFlush(e);
        return toRecord(e);
    }

    @Override
    public List<AuditEntry> query(String source, String operatorLike, int limit) {
        List<AuditLogEntity> scan = jpa.findAll().stream()
                .sorted(Comparator.comparingLong((AuditLogEntity e) -> e.seq).reversed())
                .limit(QUERY_SCAN_CAP)
                .toList();
        return scan.stream()
                .map(this::toRecord)
                .filter(e -> source == null || source.isBlank() || e.source().equals(source))
                .filter(e -> operatorLike == null || operatorLike.isBlank()
                        || e.operator().toLowerCase().contains(operatorLike.toLowerCase()))
                .limit(limit <= 0 ? 50 : Math.min(limit, QUERY_SCAN_CAP))
                .collect(Collectors.toList());
    }

    @Override
    public long count() {
        return jpa.maxSeq();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appended", jpa.maxSeq());
        m.put("inDb", jpa.count());
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> verifyChain() {
        List<AuditLogEntity> all = jpa.findAll().stream()
                .sorted(Comparator.comparingLong(e -> e.seq))
                .toList();
        String expectedPrev = GENESIS;
        for (AuditLogEntity e : all) {
            if (e.seq != 1 && !expectedPrev.equals(e.prevHash)) {
                return Map.of("valid", false, "checked", e.seq - 1, "brokenAtSeq", e.seq,
                        "reason", "prevHash 断链");
            }
            String expect = HashUtil.sha256Hex(e.seq + "|" + e.timeText + "|" + e.source
                    + "|" + e.operator + "|" + e.instanceId + "|" + e.command + "|" + e.costMs
                    + "|" + e.result + "|" + e.prevHash);
            if (!expect.equals(e.hash)) {
                return Map.of("valid", false, "checked", e.seq - 1, "brokenAtSeq", e.seq,
                        "reason", "内容摘要不匹配（记录被篡改）");
            }
            expectedPrev = e.hash;
        }
        return Map.of("valid", true, "checked", all.size(), "brokenAtSeq", 0, "reason", "");
    }

    private AuditEntry toRecord(AuditLogEntity e) {
        return new AuditEntry(e.seq, e.time, e.source, e.operator, e.instanceId,
                e.command, e.costMs, e.result, e.prevHash, e.hash);
    }

    /** 供测试与工具构造内存版 */
    public static List<AuditEntry> emptyList() {
        return new ArrayList<>();
    }
}
