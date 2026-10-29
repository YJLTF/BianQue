package com.bianque.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 节点侧 arthas 执行器：全部命令按目标 PID 附加（免同名多进程歧义）。
 * 流程：记忆会话直连 → 分配端口探测（重启恢复）→ arthas-boot <pid> --attach-only 建常驻会话
 * → agent HTTP 直连执行（毫秒级）。回传形态：快路径为 arthas agent /api 原始 JSON
 * （outputKind=agent-json，由平台统一渲染）。空闲 30 分钟自动 stop，防增强残留。
 * 子进程一律看门狗守护：读输出独立线程 + waitFor 超时强杀，杜绝交互式提示挂起 worker。
 */
public final class ArthasRunner {

    public static final class ExecResult {
        public final String output;
        public final String outputKind;
        public final String error;

        public ExecResult(String output, String outputKind, String error) {
            this.output = output;
            this.outputKind = outputKind;
            this.error = error;
        }
    }

    private static final int PORT_BASE = 28600;
    private static final long IDLE_STOP_MS = 30 * 60_000L;

    private final String bootJarPath;
    private final String arthasHome;
    private final String javaPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final int port;
        volatile long lastUse = System.currentTimeMillis();

        Session(int port) {
            this.port = port;
        }
    }

    public ArthasRunner(String bootJarPath, String javaPath) {
        this.bootJarPath = bootJarPath;
        this.arthasHome = resolveArthasHome(bootJarPath);
        this.javaPath = javaPath;
        if (arthasHome != null) {
            AgentMain.log("arthas 发行目录: " + arthasHome
                    + "（attach 显式指定，离线可用；跨用户 attach 需该目录对目标用户可读）");
        } else {
            AgentMain.log("未检测到完整 arthas 发行目录（arthas-core.jar 需与 arthas-boot.jar 同目录），"
                    + "attach 时将由 arthas-boot 自行解析");
        }
        Thread reaper = new Thread(() -> {
            while (true) {
                try {
                    TimeUnit.MINUTES.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (Map.Entry<Long, Session> en : sessions.entrySet()) {
                    if (now - en.getValue().lastUse > IDLE_STOP_MS) {
                        sessions.remove(en.getKey());
                        AgentMain.log("会话空闲超时，自动 stop（pid=" + en.getKey() + "）");
                        stopAgent(en.getValue().port);
                    }
                }
            }
        }, "bq-agent-session-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    public ExecResult exec(long pid, String mainClass, String command) {
        if ("__close__".equals(command)) {
            return closeSession(pid, mainClass);
        }
        // 1) 本节点记忆的常驻会话
        Session s = sessions.get(pid);
        if (s != null && alive(s.port)) {
            ExecResult r = httpExec(s.port, command);
            if (r.error == null) {
                s.lastUse = System.currentTimeMillis();
                return r;
            }
            sessions.remove(pid);
        }
        // 2) 分配端口探测（本 agent 早前运行遗留的会话恢复）
        int port = allocate(mainClass);
        if ((s == null || s.port != port) && alive(port)) {
            ExecResult r = httpExec(port, command);
            if (r.error == null) {
                sessions.put(pid, new Session(port));
                return r;
            }
        }
        // 3) 按.pid attach-only 建常驻会话后执行（免同名多进程歧义）
        int attached = attachOnly(pid, port);
        if (attached < 0) {
            return new ExecResult(null, null, "attach 失败：无法附加到目标进程 " + pid
                    + "（进程是否存活、与节点同用户，且 arthas 发行目录对目标用户可读？）");
        }
        ExecResult r = httpExec(attached, command);
        if (r.error != null) {
            return new ExecResult(null, null, "会话已建立但执行失败: " + r.error);
        }
        sessions.put(pid, new Session(attached));
        return r;
    }

    private ExecResult closeSession(long pid, String mainClass) {
        Session s = sessions.remove(pid);
        boolean stopped = false;
        if (s != null) {
            stopped = stopAgent(s.port);
        } else {
            int port = allocate(mainClass);
            stopped = alive(port) && stopAgent(port);
        }
        return new ExecResult(stopped ? "会话已回收（arthas agent 已 stop）" : "无活跃会话", "text", null);
    }

    private boolean alive(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"exec\",\"command\":\"version\"}"))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("version");
        } catch (Exception e) {
            return false;
        }
    }

    private ExecResult httpExec(int port, String command) {
        try {
            String payload = MiniJson.write(Map.of("action", "exec", "command", command));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api"))
                    .timeout(Duration.ofSeconds(65))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new ExecResult(null, null, "arthas HTTP " + resp.statusCode());
            }
            return new ExecResult(resp.body(), "agent-json", null);
        } catch (Exception e) {
            return new ExecResult(null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private boolean stopAgent(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api"))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"exec\",\"command\":\"stop\"}"))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static int allocate(String mainClass) {
        return PORT_BASE + Math.abs(mainClass.hashCode() % 1000);
    }

    /** java -jar arthas-boot.jar <pid> --attach-only --http-port P --telnet-port P+1（按.pid 免歧义）；
     *  检测到完整发行目录时追加 --arthas-home（见 resolveArthasHome） */
    private int attachOnly(long pid, int httpPort) {
        Process p = null;
        try {
            List<String> cmd = new ArrayList<>(List.of(javaPath, "-jar", bootJarPath,
                    String.valueOf(pid), "--attach-only",
                    "--http-port", String.valueOf(httpPort),
                    "--telnet-port", String.valueOf(httpPort + 1)));
            if (arthasHome != null) {
                cmd.add("--arthas-home");
                cmd.add(arthasHome);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process fp = p;
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(fp.getInputStream(), StandardCharsets.UTF_8))) {
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
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                AgentMain.log("attach-only 超时被强杀（pid=" + pid + "）");
                return -1;
            }
            if (p.exitValue() != 0) {
                String tail = out.substring(Math.max(0, out.length() - 300));
                AgentMain.log("attach-only 失败（pid=" + pid + "）: " + tail.replace('\n', ' '));
                return -1;
            }
            for (int i = 0; i < 20; i++) {
                if (alive(httpPort)) {
                    AgentMain.log("常驻会话已建立: pid=" + pid + " @ http-port " + httpPort);
                    return httpPort;
                }
                TimeUnit.MILLISECONDS.sleep(500);
            }
            AgentMain.log("attach 成功但端口 " + httpPort + " 未就绪（pid=" + pid + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            AgentMain.log("attach-only 异常: " + e.getMessage());
            if (p != null) {
                p.destroyForcibly();
            }
        }
        return -1;
    }

    /**
     * arthas-boot.jar 同目录存在 arthas-core.jar（完整发行包）时返回该目录，attach 显式 --arthas-home：
     * 1) agent jar 由目标 JVM 以其自身用户打开——发行目录对目标用户可读是跨用户 attach 的前提
     *    （root 探针 attach 非 root 应用时，arthas-boot 缺省解压到 /root/.arthas，目标进程读不到必失败）；
     * 2) 离线内网免运行时下载发行包。
     */
    static String resolveArthasHome(String bootJarPath) {
        try {
            Path dir = Paths.get(bootJarPath).toAbsolutePath().getParent();
            if (dir != null && Files.exists(dir.resolve("arthas-core.jar"))) {
                return dir.toString();
            }
        } catch (Exception ignore) {
            // 路径异常时退回 arthas-boot 缺省行为
        }
        return null;
    }
}
