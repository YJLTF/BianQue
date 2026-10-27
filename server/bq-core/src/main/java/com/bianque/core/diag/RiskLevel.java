package com.bianque.core.diag;

/**
 * 命令风险等级（与文档中心 arthas-docs-manifest.json 的 risk 字段一致）。
 */
public enum RiskLevel {
    /** 只读观测，如 dashboard/jvm/memory/thread */
    LOW,
    /** 字节码增强 / 采样类，有持续开销，需限次限频，如 watch/trace/profiler */
    MID,
    /** 等价于进程内任意代码执行或数据导出，需双人审批，如 ognl/redefine/heapdump */
    HIGH;

    public boolean requireApproval() {
        return this == HIGH;
    }
}
