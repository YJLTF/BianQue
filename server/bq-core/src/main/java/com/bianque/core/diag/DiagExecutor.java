package com.bianque.core.diag;

/**
 * 执行引擎抽象。S1 提供两个通道实现：
 * 批量任务走 as.sh --batch-file（稳定可回放），实时会话走 HTTP API 8563（低延迟）。
 */
public interface DiagExecutor {

    DiagResult execute(DiagCommand command);
}
