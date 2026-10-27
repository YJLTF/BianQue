package com.bianque.server.repo;

import com.bianque.server.model.Types.AuditEntry;
import java.util.List;

/**
 * 审计留痕抽象：执行链路依赖此接口，落库实现可替换（S1 H2/MySQL）。
 */
public interface AuditTrail {

    AuditEntry append(String source, String operator, String instanceId,
                      String command, long costMs, String result);

    /** 倒序查询，支持来源与操作人过滤 */
    List<AuditEntry> query(String source, String operatorLike, int limit);

    long count();

    /** 哈希链完整性校验：返回 {valid, checked, brokenAtSeq, reason} */
    java.util.Map<String, Object> verifyChain();
}
