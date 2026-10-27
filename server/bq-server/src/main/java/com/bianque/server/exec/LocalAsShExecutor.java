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
 * 本机真实通道：经发行包 arthas-boot.jar 非交互执行（--select 目标 + -c 命令批处理）。
 * 纯 Java 进程链（java -jar arthas-boot.jar），不依赖系统 telnet（内网禁 telnet 环境可用）。
 * 4.3.5 的 arthas-boot 无 --execution-timeout（那是 as.sh 包装参数），超时由进程级 waitFor 兜底。
 * 仅对 localAgent 实例启用；bianque.execution.local-enabled=false 时整体不装配（默认）。
 */
public class LocalAsShExecutor implements DiagExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalAsShExecutor.class);
    private static final String ANSI = "\u001B\\[[0-9;]*[A-Za-z]";

    private final String bootJarPath;
    private final String javaPath;

    public LocalAsShExecutor(
            @Value("${bianque.execution.boot-jar-path:docs/research/arthas-inspect/arthas-boot.jar}") String bootJarPath,
            @Value("${bianque.execution.java-path:${java.home}/bin/java}") String javaPath) {
        this.bootJarPath = bootJarPath;
        this.javaPath = javaPath;
    }

    @Override
    public DiagResult execute(DiagCommand command) {
        long begin = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    javaPath, "-jar", bootJarPath,
                    "--select", command.appName(),
                    "-c", command.commandText());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String raw = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(command.timeoutSeconds() + 30L, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return DiagResult.fail("执行超时被终止（含 attach 时间）", System.currentTimeMillis() - begin);
            }
            String out = stripBootNoise(raw);
            return p.exitValue() == 0 && !out.isBlank()
                    ? DiagResult.ok(out, System.currentTimeMillis() - begin)
                    : DiagResult.fail(out.isBlank() ? "exit=" + p.exitValue() + "（无输出）" : out,
                            System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.warn("真实通道执行失败: {}", e.getMessage());
            return DiagResult.fail("真实通道不可用: " + e.getMessage(), System.currentTimeMillis() - begin);
        }
    }

    /** 剥离 ANSI 颜色与 boot banner/attach 日志，从命令提示符行后取纯命令输出 */
    static String stripBootNoise(String raw) {
        String out = raw.replaceAll(ANSI, "").replace("\r\n", "\n");
        // arthas 批处理输出以 "arthas@<pid>$ <命令>" 提示符行开始
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\[arthas@\\d+\\]\\$ ([^\\n]*)\\n").matcher(out);
        if (m.find()) {
            out = out.substring(m.end());
        }
        // 尾部的断开提示与提示符残行
        out = out.replaceAll("\\[arthas@\\d+\\]\\$?\\s*$", "")
                .replace("Command execution started", "").trim();
        return out;
    }
}
