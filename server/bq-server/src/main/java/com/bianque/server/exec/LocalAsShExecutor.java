package com.bianque.server.exec;

import com.bianque.core.diag.DiagCommand;
import com.bianque.core.diag.DiagExecutor;
import com.bianque.core.diag.DiagResult;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * 本机 as.sh 通道：通过 bash 调用发行包 as.sh 非交互执行（--select/--command/--execution-timeout）。
 * 仅对 localAgent 实例启用；bianque.execution.mode=mock 时整体禁用（默认）。
 */
public class LocalAsShExecutor implements DiagExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalAsShExecutor.class);

    private final String asShPath;

    public LocalAsShExecutor(@Value("${bianque.execution.as-sh-path:docs/research/arthas-inspect/as.sh}") String asShPath) {
        this.asShPath = asShPath;
    }

    @Override
    public DiagResult execute(DiagCommand command) {
        long begin = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "bash", asShPath,
                    "--select", command.appName(),
                    "--command", command.commandText(),
                    "--execution-timeout", String.valueOf(command.timeoutSeconds()));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(command.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return DiagResult.fail("execution-timeout 超时终止", System.currentTimeMillis() - begin);
            }
            return p.exitValue() == 0
                    ? DiagResult.ok(out, System.currentTimeMillis() - begin)
                    : DiagResult.fail(out.isBlank() ? "exit=" + p.exitValue() : out, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.warn("as.sh 通道执行失败: {}", e.getMessage());
            return DiagResult.fail("as.sh 通道不可用: " + e.getMessage(), System.currentTimeMillis() - begin);
        }
    }
}
