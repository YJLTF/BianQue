package com.bianque.core.diag;

/**
 * 一次诊断命令的完整描述（所见即所执行：commandText 即最终下发原文）。
 */
public record DiagCommand(
        String appName,
        String instanceId,
        String family,
        RiskLevel risk,
        String commandText,
        int timeoutSeconds) {

    public DiagCommand {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName 不能为空");
        }
        if (commandText == null || commandText.isBlank()) {
            throw new IllegalArgumentException("commandText 不能为空");
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }
    }
}
