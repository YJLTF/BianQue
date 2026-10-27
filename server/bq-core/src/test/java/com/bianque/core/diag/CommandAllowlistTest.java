package com.bianque.core.diag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandAllowlistTest {

    private final CommandAllowlist allowlist = new CommandAllowlist();

    @Test
    void 高危命令默认被拦截() {
        assertFalse(allowlist.isAllowed("ognl '@System@getProperty(\"user.dir\")'"));
        assertFalse(allowlist.isAllowed("redefine /tmp/A.class"));
        assertFalse(allowlist.isAllowed("heapdump --live /tmp/h.hprof"));
    }

    @Test
    void 观测与增强类命令放行() {
        assertTrue(allowlist.isAllowed("thread -n 3"));
        assertTrue(allowlist.isAllowed("memory"));
        assertTrue(allowlist.isAllowed("trace com.bianque.OrderService placeOrder '#cost>100'"));
    }

    @Test
    void 空命令不放行() {
        assertFalse(allowlist.isAllowed(" "));
        assertFalse(allowlist.isAllowed(null));
    }
}
