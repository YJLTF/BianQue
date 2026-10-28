package com.bianque.server.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 生产模式安全门禁单测：demo 关闭时节点令牌必须配置（fail-fast），演示模式保持开放接入。
 */
class SecurityGuardTest {

    @Test
    void 生产模式缺令牌违规() {
        assertNotNull(SecurityGuard.violation(false, null), "生产模式空令牌必须判定违规");
        assertNotNull(SecurityGuard.violation(false, "  "), "生产模式空白令牌必须判定违规");
    }

    @Test
    void 生产模式有令牌通过() {
        assertNull(SecurityGuard.violation(false, "agent-token-123"));
    }

    @Test
    void 演示模式开放接入通过() {
        assertNull(SecurityGuard.violation(true, null), "演示模式（内网）允许开放接入");
        assertNull(SecurityGuard.violation(true, "t"));
    }
}
