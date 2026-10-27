package com.bianque.core.diag;

import java.util.Set;

/**
 * 命令白名单 / 黑名单基线（安全红线：平台是唯一执行入口）。
 * 默认禁用清单与下发到 agent 的 arthas.disabledCommands 保持一致。
 */
public final class CommandAllowlist {

    /** 默认黑名单：等价于任意代码执行 / 热修改 / 数据导出（设计方案 6.1） */
    public static final Set<String> DEFAULT_BLOCKED = Set.of(
            "ognl", "redefine", "retransform", "mc", "groovy", "heapdump");

    /** 增强观测类：允许但必须限次限频（-n / execution-timeout） */
    public static final Set<String> ENHANCED = Set.of(
            "watch", "trace", "tt", "monitor", "stack", "line", "profiler", "jfr");

    private final Set<String> blocked;

    public CommandAllowlist() {
        this(DEFAULT_BLOCKED);
    }

    public CommandAllowlist(Set<String> blocked) {
        this.blocked = Set.copyOf(blocked);
    }

    /** 命令是否被放行（按命令名首个 token 判定） */
    public boolean isAllowed(String commandText) {
        if (commandText == null) {
            return false;
        }
        String head = commandText.trim().split("\\s+")[0].toLowerCase();
        return !head.isEmpty() && !blocked.contains(head);
    }

    public Set<String> blockedCommands() {
        return blocked;
    }
}
