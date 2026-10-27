package com.bianque.core.diag;

/**
 * 执行结果（结构化，供前端结论区与审计中心消费）。
 */
public record DiagResult(
        boolean success,
        String output,
        long costMs,
        String error) {

    public static DiagResult ok(String output, long costMs) {
        return new DiagResult(true, output, costMs, null);
    }

    public static DiagResult fail(String error, long costMs) {
        return new DiagResult(false, null, costMs, error);
    }
}
