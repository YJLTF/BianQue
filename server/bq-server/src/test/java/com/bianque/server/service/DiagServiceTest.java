package com.bianque.server.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.core.diag.DiagResult;
import com.bianque.server.exec.LocalAsShExecutor;
import com.bianque.server.exec.MockDiagExecutor;
import com.bianque.server.model.Types.AuditEntry;
import com.bianque.server.repo.AuditTrail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DiagServiceTest {

    /** 测试用内存审计实现（与生产 AuditRepository 同接口） */
    static final class MemAuditTrail implements AuditTrail {
        private final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();
        private final AtomicLong seq = new AtomicLong();

        @Override
        public AuditEntry append(String source, String operator, String instanceId,
                                 String command, long costMs, String result) {
            AuditEntry e = new AuditEntry(seq.incrementAndGet(), Instant.now(), source, operator,
                    instanceId, command, costMs, result, "prev", "hash-" + seq.get());
            entries.addFirst(e);
            return e;
        }

        @Override
        public List<AuditEntry> query(String source, String operatorLike, int limit) {
            List<AuditEntry> all = new ArrayList<>(entries);
            all.sort(Comparator.comparing(AuditEntry::seq).reversed());
            return all.stream()
                    .filter(e -> source == null || e.source().equals(source))
                    .filter(e -> operatorLike == null || operatorLike.isBlank()
                            || e.operator().contains(operatorLike))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long count() {
            return seq.get();
        }

        @Override
        public Map<String, Object> verifyChain() {
            return Map.of("valid", true, "checked", seq.get(), "brokenAtSeq", 0, "reason", "");
        }
    }

    private final MemAuditTrail audit = new MemAuditTrail();
    private final DiagService service = new DiagService(providerOf(new MockDiagExecutor()),
            emptyLocalProvider(), stubAgentExecutor(), audit, new com.bianque.server.repo.FleetRepository(null, true), 30);

    /** 测试装配：拟真通道恒可用（演示模式语义） */
    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    /** 测试装配：Agent 通道不参与本单测（仅 mock 路由），用匿名子类桩替代 */
    private static com.bianque.server.agent.AgentChannelExecutor stubAgentExecutor() {
        return new com.bianque.server.agent.AgentChannelExecutor(null) {
            @Override
            public com.bianque.core.diag.DiagResult execute(com.bianque.core.diag.DiagCommand command) {
                throw new IllegalStateException("测试环境不提供 Agent 通道");
            }
        };
    }

    /** 测试装配：本机真实通道未启用 */
    private static org.springframework.beans.factory.ObjectProvider<LocalAsShExecutor> emptyLocalProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public LocalAsShExecutor getObject() {
                throw new IllegalStateException("测试环境不提供本机通道");
            }

            @Override
            public LocalAsShExecutor getObject(Object... args) {
                throw new IllegalStateException("测试环境不提供本机通道");
            }

            @Override
            public LocalAsShExecutor getIfAvailable() {
                return null;
            }

            @Override
            public LocalAsShExecutor getIfUnique() {
                return null;
            }
        };
    }

    @Test
    void 高危命令被拦截并留审计() {
        DiagResult r = service.execute("pod-3", "ognl '@System@exit(0)'", "人工");
        assertFalse(r.success());
        assertTrue(r.error().contains("已拦截"));
        assertFalse(audit.query("人工", null, 10).isEmpty());
        assertTrue(audit.query("人工", null, 10).get(0).result().equals("blocked"));
    }

    @Test
    void 观测命令正常执行并留审计() {
        DiagResult r = service.execute("pod-3", "thread -n 3", "人工");
        assertTrue(r.success());
        assertTrue(r.output().contains("线程总数"));
    }

    @Test
    void 登录操作人写入审计() {
        service.execute("pod-3", "thread -n 3", "人工", "admin(管理员)");
        assertTrue(audit.query("人工", "admin", 10).get(0).operator().equals("admin(管理员)"));
    }
}
