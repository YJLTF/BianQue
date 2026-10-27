package com.bianque.core.exec;

import com.bianque.core.diag.DiagCommand;

/**
 * 渲染 as.sh 非交互命令行（批量通道）。纯函数，便于审计回放与单元测试。
 * 对应发行包实测用法：as.sh --select 进程 --command "cmd" --execution-timeout 秒
 */
public final class AsShCommandLine {

    private AsShCommandLine() {
    }

    public static String render(DiagCommand command, String selectProcess) {
        String process = (selectProcess == null || selectProcess.isBlank())
                ? command.appName() : selectProcess;
        String cmd = command.commandText().replace("\"", "\\\"");
        return "as.sh --select " + process
                + " --command \"" + cmd + "\""
                + " --execution-timeout " + command.timeoutSeconds();
    }
}
