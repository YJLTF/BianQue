package com.bianque.server.exec;

import com.bianque.server.exec.FastPathClient.ExecOut;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * 本机 arthas 常驻会话管理：一次 attach（--attach-only），后续命令直连 agent HTTP 端口（毫秒级）。
 * - 端口分配：base + hash(selectTarget)%区间，attach-only 时显式指定，重启后可重新探测；
 * - 探测顺序：记忆端口 → 默认 8563 → 分配端口；探测失败才 attach-only；
 * - attach-only 失败（如目标已被未知端口的 arthas 占用）由调用方回落一次性 boot 通道。
 */
public class LocalArthasSessionManager {

    private static final Logger log = LoggerFactory.getLogger(LocalArthasSessionManager.class);
    private static final int DEFAULT_AGENT_PORT = 8563;

    private final Map<String, Integer> ports = new ConcurrentHashMap<>();
    private final FastPathClient client;
    private final String bootJarPath;
    private final String javaPath;
    private final int portBase;
    private final boolean enabled;

    public LocalArthasSessionManager(FastPathClient client,
                                     @Value("${bianque.execution.boot-jar-path:docs/research/arthas-inspect/arthas-boot.jar}") String bootJarPath,
                                     @Value("${bianque.execution.java-path:${java.home}/bin/java}") String javaPath,
                                     @Value("${bianque.execution.fast-port-base:18600}") int portBase,
                                     @Value("${bianque.execution.fast-path:true}") boolean enabled) {
        this.client = client;
        this.bootJarPath = bootJarPath;
        this.javaPath = javaPath;
        this.portBase = portBase;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 执行命令：优先常驻 HTTP 通道；失败返回 error，由调用方回落一次性通道。
     */
    public ExecOut exec(String selectTarget, String command) {
        if (!enabled) {
            return new ExecOut(null, "fast-path disabled");
        }
        try {
            int port = ensureAttached(selectTarget);
            if (port > 0) {
                ExecOut out = client.exec(port, command);
                if (out.error() == null) {
                    return out;
                }
                log.info("常驻通道执行失败（{}），回落一次性通道", out.error());
                ports.remove(selectTarget);
            }
        } catch (Exception e) {
            log.info("常驻通道异常，回落一次性通道: {}", e.getMessage());
        }
        return new ExecOut(null, "fast-path unavailable");
    }

    /** 停止目标 agent（会话回收时调用，卸载增强防残留） */
    public boolean stop(String selectTarget) {
        Integer port = ports.remove(selectTarget);
        if (port == null) {
            port = probeCandidates(selectTarget);
        }
        return port != null && client.stop(port);
    }

    /** 确保目标已 attach 且端口可用，返回 HTTP 端口；失败返回 -1 */
    public synchronized int ensureAttached(String selectTarget) {
        Integer known = ports.get(selectTarget);
        if (known != null && client.probe(known)) {
            return known;
        }
        Integer probed = probeCandidates(selectTarget);
        if (probed != null) {
            ports.put(selectTarget, probed);
            return probed;
        }
        return attachOnly(selectTarget);
    }

    /** 探测：记忆端口 → 默认端口 → 本实例分配端口（重启后恢复用） */
    private Integer probeCandidates(String selectTarget) {
        List<Integer> candidates = new ArrayList<>(List.of(DEFAULT_AGENT_PORT, allocate(selectTarget)));
        Integer known = ports.get(selectTarget);
        if (known != null) {
            candidates.add(0, known);
        }
        for (int p : candidates) {
            if (client.probe(p)) {
                return p;
            }
        }
        return null;
    }

    private int allocate(String selectTarget) {
        int span = 3800 - portBase > 0 ? 3800 - portBase : 1;
        return portBase + Math.abs(selectTarget.hashCode() % span);
    }

    /** java -jar arthas-boot.jar <pid> --attach-only --http-port P --telnet-port P+1（selectTarget=pid，免同名歧义） */
    private int attachOnly(String selectTarget) {
        int http = allocate(selectTarget);
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    javaPath, "-jar", bootJarPath,
                    selectTarget, "--attach-only",
                    "--http-port", String.valueOf(http),
                    "--telnet-port", String.valueOf(http + 1));
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process fp = p;
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                } catch (Exception ignore) {
                    // 进程被强杀时流关闭，读循环自然结束
                }
            }, "arthas-attach-reader");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.info("attach-only 超时被强杀（target {}）", selectTarget);
                return -1;
            }
            if (p.exitValue() != 0) {
                log.info("attach-only 未成功（target {}），输出尾行: {}",
                        selectTarget, out.substring(Math.max(0, out.length() - 200)).replace('\n', ' '));
                return -1;
            }
            for (int i = 0; i < 20; i++) {
                if (client.probe(http)) {
                    log.info("常驻会话已建立: {} @ http-port {}", selectTarget, http);
                    ports.put(selectTarget, http);
                    return http;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            log.info("attach-only 异常: {}", e.getMessage());
            if (p != null) {
                p.destroyForcibly();
            }
        }
        return -1;
    }
}
