package com.bianque.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.core.diag.CommandAllowlist;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 高危黑名单唯一裁决点：生效黑名单 = 内置基线 ∪ 参数中心追加项；基线恒拦截不可削减，追加项只能加严。
 */
class BlacklistServiceTest {

    private final SysConfigService config = SysConfigService.inMemory(new SysConfigServiceTest.MemAuditTrail());
    private final BlacklistService blacklist = new BlacklistService(config);

    @Test
    void 默认仅内置基线生效() {
        assertEquals(CommandAllowlist.DEFAULT_BLOCKED, blacklist.baseline());
        assertEquals(CommandAllowlist.DEFAULT_BLOCKED, blacklist.effective());
        assertTrue(blacklist.extra().isEmpty());
        assertFalse(blacklist.isAllowed("ognl '@System@exit(0)'"));
        assertFalse(blacklist.isAllowed("heapdump /tmp/h.hprof"));
        assertTrue(blacklist.isAllowed("thread -n 5"));
        assertTrue(blacklist.isAllowed("jad com.demo.OrderService"));
    }

    @Test
    void 追加项加严且基线恒在() {
        config.update(BlacklistService.EXTRA_KEY, "jad sc", "tester");
        assertEquals(Set.of("jad", "sc"), Set.copyOf(blacklist.extra()));
        assertFalse(blacklist.isAllowed("jad com.demo.OrderService"));
        assertFalse(blacklist.isAllowed("sc -d com.demo.OrderService"));
        // 命令首 token 大写同口径归一拦截
        assertFalse(blacklist.isAllowed("SC -d x"));
        // 基线不受追加项增减影响
        assertTrue(blacklist.effective().containsAll(CommandAllowlist.DEFAULT_BLOCKED));
        assertFalse(blacklist.isAllowed("groovy 'x'"));
        // 清空追加项后恢复仅基线
        config.update(BlacklistService.EXTRA_KEY, "", "tester");
        assertTrue(blacklist.isAllowed("jad com.demo.OrderService"));
        assertEquals(CommandAllowlist.DEFAULT_BLOCKED, blacklist.effective());
    }
}
