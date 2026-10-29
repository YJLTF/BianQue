package com.bianque.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.server.model.Types.AuditEntry;
import com.bianque.server.repo.AuditTrail;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 参数中心：类型/范围/关联约束校验、修改即时生效、恢复默认、审计留痕（内存模式） */
class SysConfigServiceTest {

    static final class MemAuditTrail implements AuditTrail {
        final ConcurrentLinkedDeque<AuditEntry> entries = new ConcurrentLinkedDeque<>();
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
            return List.of();
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
    private final SysConfigService config = SysConfigService.inMemory(audit);

    @Test
    void 默认值读取与即时生效() {
        assertEquals(60, config.intValue("diag.command-timeout-seconds"));
        config.update("diag.command-timeout-seconds", "15", "tester");
        assertEquals(15, config.intValue("diag.command-timeout-seconds"));
        assertTrue((Boolean) config.list().stream()
                .filter(m -> "diag.command-timeout-seconds".equals(m.get("key")))
                .findFirst().orElseThrow().get("customized"));
        assertEquals(1, audit.entries.size());
    }

    @Test
    void 范围与非整数被拒绝() {
        assertThrows(ResponseStatusException.class,
                () -> config.update("diag.command-timeout-seconds", "1", "tester"));
        assertThrows(ResponseStatusException.class,
                () -> config.update("diag.command-timeout-seconds", "9999", "tester"));
        assertThrows(ResponseStatusException.class,
                () -> config.update("diag.command-timeout-seconds", "abc", "tester"));
        assertThrows(ResponseStatusException.class,
                () -> config.update("diag.command-timeout-seconds", "", "tester"));
        assertEquals(60, config.intValue("diag.command-timeout-seconds"));
    }

    @Test
    void 心跳与在线窗口联动约束() {
        // 默认 5/20 合法；窗口必须先行放宽（30 ≥ 3×10），才能把心跳调到 10
        config.update("agent.online-window-seconds", "30", "tester");
        config.update("agent.heartbeat-interval-seconds", "10", "tester");
        assertThrows(ResponseStatusException.class,
                () -> config.update("agent.online-window-seconds", "25", "tester"));
        // 反向：窗口 30 时心跳最多 10
        assertThrows(ResponseStatusException.class,
                () -> config.update("agent.heartbeat-interval-seconds", "11", "tester"));
        assertEquals(10, config.intValue("agent.heartbeat-interval-seconds"));
        assertEquals(30, config.intValue("agent.online-window-seconds"));
    }

    @Test
    void 巡检总时限不小于单实例时限() {
        assertThrows(ResponseStatusException.class,
                () -> config.update("checkup.instance-timeout-seconds", "200", "tester"));
        config.update("checkup.deadline-seconds", "300", "tester");
        config.update("checkup.instance-timeout-seconds", "200", "tester");
        assertEquals(300, config.intValue("checkup.deadline-seconds"));
    }

    @Test
    void 恢复默认与未知参数() {
        config.update("diag.session-ttl-minutes", "60", "tester");
        config.reset("diag.session-ttl-minutes", "tester");
        assertEquals(30, config.intValue("diag.session-ttl-minutes"));
        assertFalse((Boolean) config.list().stream()
                .filter(m -> "diag.session-ttl-minutes".equals(m.get("key")))
                .findFirst().orElseThrow().get("customized"));
        assertThrows(ResponseStatusException.class, () -> config.update("no.such.key", "1", "tester"));
    }

    @Test
    void 黑名单追加项_归一去重与即时生效() {
        assertEquals(List.of(), config.listValue("diag.blocked-extra-commands"));
        config.update("diag.blocked-extra-commands", "jad, sc", "tester");
        assertEquals(List.of("jad", "sc"), config.listValue("diag.blocked-extra-commands"));
        // 大小写归一 + 多种分隔符 + 去重
        config.update("diag.blocked-extra-commands", "JAD　jad ，sc", "tester");
        assertEquals(List.of("jad", "sc"), config.listValue("diag.blocked-extra-commands"));
        assertEquals(2, audit.entries.size());
        // customized 标记
        assertTrue((Boolean) config.list().stream()
                .filter(m -> "diag.blocked-extra-commands".equals(m.get("key")))
                .findFirst().orElseThrow().get("customized"));
    }

    @Test
    void 黑名单追加项_非法输入被拒绝且不落值() {
        // 全角空格/逗号分隔解析后 "-rf" 非法 token → 整体拒绝
        assertThrows(ResponseStatusException.class,
                () -> config.update("diag.blocked-extra-commands", "jad　rm -rf", "tester"));
        // 数字开头非命令 token
        assertThrows(ResponseStatusException.class,
                () -> config.update("diag.blocked-extra-commands", "watch 1x", "tester"));
        // 内置基线命令拒绝追加（已恒拦截，无需追加）
        assertThrows(ResponseStatusException.class,
                () -> config.update("diag.blocked-extra-commands", "ognl", "tester"));
        assertEquals(List.of(), config.listValue("diag.blocked-extra-commands"));
        // 空值 = 清空追加项（合法操作）
        config.update("diag.blocked-extra-commands", "jad", "tester");
        config.update("diag.blocked-extra-commands", "  ", "tester");
        assertEquals(List.of(), config.listValue("diag.blocked-extra-commands"));
    }
}
