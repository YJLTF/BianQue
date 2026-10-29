package com.bianque.server.service;

import com.bianque.core.diag.CommandAllowlist;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 高危黑名单唯一裁决点：生效黑名单 = 内置基线 ∪ 参数中心追加项。
 * 内置基线（ognl/redefine/retransform/mc/groovy/heapdump）为安全红线：不可放行、不可配置削减；
 * 追加项（diag.blocked-extra-commands）只能加严，由管理员在页面维护、改动入审计。
 * 人工诊断与 AI 会诊统一经此判定，保证两条链路口径一致。
 */
@Service
public class BlacklistService {

    /** 参数中心追加项 key（list 型） */
    public static final String EXTRA_KEY = "diag.blocked-extra-commands";

    private final SysConfigService config;

    public BlacklistService(SysConfigService config) {
        this.config = config;
    }

    /** 内置基线（安全红线，恒拦截） */
    public Set<String> baseline() {
        return CommandAllowlist.DEFAULT_BLOCKED;
    }

    /** 页面追加的加严项 */
    public List<String> extra() {
        return config.listValue(EXTRA_KEY);
    }

    /** 生效黑名单 = 基线 ∪ 追加 */
    public Set<String> effective() {
        Set<String> all = new LinkedHashSet<>(baseline());
        all.addAll(extra());
        return Set.copyOf(all);
    }

    /** 命令是否放行（按命令名首个 token 判定，与 CommandAllowlist 同口径） */
    public boolean isAllowed(String commandText) {
        return new CommandAllowlist(effective()).isAllowed(commandText);
    }
}
