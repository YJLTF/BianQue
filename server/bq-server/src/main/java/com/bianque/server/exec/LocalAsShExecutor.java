package com.bianque.server.exec;

import com.bianque.core.diag.DiagCommand;
import com.bianque.core.diag.DiagExecutor;
import com.bianque.core.diag.DiagResult;
import com.bianque.server.exec.FastPathClient.ExecOut;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * 本机真实通道：
 * 1) 快路径——常驻 agent HTTP 直连（仅 version/jvm/memory/thread 等平台已适配渲染的热命令，毫秒级）；
 * 2) 兜底——一次性 arthas-boot 批处理（全命令可用，秒级），快路径不可用或冷命令时走此路径。
 * 兜底命令行：java -jar arthas-boot.jar --select <主类> -c "<命令>"，纯 Java 无 telnet 依赖。
 */
public class LocalAsShExecutor implements DiagExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalAsShExecutor.class);
    private static final String ANSI = "\u001B\\[[0-9;]*[A-Za-z]";
    /** 快路径白名单：FastPathClient 已实现文本渲染的命令族 */
    private static final Set<String> FAST_COMMANDS = Set.of("version", "jvm", "memory", "thread");

    private final String bootJarPath;
    private final String javaPath;
    private final LocalArthasSessionManager sessions; // 可为 null（fast-path 关闭时）

    public LocalAsShExecutor(
            @Value("${bianque.execution.boot-jar-path:docs/research/arthas-inspect/arthas-boot.jar}") String bootJarPath,
            @Value("${bianque.execution.java-path:${java.home}/bin/java}") String javaPath,
            LocalArthasSessionManager sessions) {
        this.bootJarPath = bootJarPath;
        this.javaPath = javaPath;
        this.sessions = sessions;
        String home = resolveArthasHome(bootJarPath);
        if (home != null) {
            log.info("arthas 发行目录: {}（attach 显式指定，离线可用；跨用户 attach 需该目录对目标用户可读）", home);
        }
    }

    /**
     * arthas-boot.jar 同目录存在 arthas-core.jar（完整发行包）时返回该目录，attach 显式 --arthas-home：
     * agent jar 由目标 JVM 以其自身用户打开，发行目录对目标用户可读是跨用户 attach 的前提；
     * 同时离线内网免运行时下载。与 bq-agent ArthasRunner 同口径。
     */
    static String resolveArthasHome(String bootJarPath) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(bootJarPath).toAbsolutePath().getParent();
            if (dir != null && java.nio.file.Files.exists(dir.resolve("arthas-core.jar"))) {
                return dir.toString();
            }
        } catch (Exception ignore) {
            // 路径异常时退回 arthas-boot 缺省行为
        }
        return null;
    }

    @Override
    public DiagResult execute(DiagCommand command) {
        long begin = System.currentTimeMillis();
        String head = command.commandText().trim().split("\\s+")[0].toLowerCase();
        if (sessions != null && sessions.enabled() && FAST_COMMANDS.contains(head)) {
            ExecOut out = sessions.exec(command.appName(), command.commandText());
            if (out.output() != null) {
                return DiagResult.ok(out.output(), System.currentTimeMillis() - begin);
            }
        }
        return oneShotBoot(command, begin);
    }

    /** 会话回收时卸载目标 agent（reset+stop 防增强残留） */
    public boolean stopAgent(String selectTarget) {
        return sessions != null && sessions.stop(selectTarget);
    }

    private DiagResult oneShotBoot(DiagCommand command, long begin) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                    javaPath, "-jar", bootJarPath,
                    "--select", command.appName(),
                    "-c", command.commandText()));
            String home = resolveArthasHome(bootJarPath);
            if (home != null) {
                cmd.add("--arthas-home");
                cmd.add(home);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            // stdin 接空设备：目标出现同名多进程歧义时 boot 会退化交互选择，必须让其立即 EOF 失败而非挂起
            pb.redirectInput(new java.io.File(
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null"));
            Process p = pb.start();
            final Process fp = p;
            StringBuilder raw = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        raw.append(line).append('\n');
                    }
                } catch (Exception ignore) {
                    // 进程退出/强杀时流关闭
                }
            }, "arthas-oneshot-reader");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(command.timeoutSeconds() + 30L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return DiagResult.fail("执行超时被终止（含 attach 时间）", System.currentTimeMillis() - begin);
            }
            String out = stripBootNoise(raw.toString());
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
