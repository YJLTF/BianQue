package com.bianque.agent;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扁鹊平台 Agent（平台版）：
 * - 部署在目标主机，纯出站 HTTP 与平台通信（默认 5s 心跳），目标主机无需开放入站端口；
 * - 心跳上报本机 JVM 进程清单，并领取平台命令单（同一时刻至多一单，串行执行）；
 * - 命令执行走 arthas：常驻会话快路径（attach-only 后 HTTP 直连）+ 一次性 boot 兜底；
 * - 运行要求：JDK 11+，arthas-boot.jar 与本 jar 同目录（或 --arthas 指定），零第三方依赖。
 *
 * 启动：java -jar bq-agent.jar --server http://平台地址:8080 --token <bianque.agent.token>
 */
public final class AgentMain {

    static final String VERSION = "1.1.0";
    private static final Path ID_FILE = Path.of("bq-agent.id");

    private AgentMain() {
    }

    public static void main(String[] args) {
        String server = null;
        String token = "";
        String name = null;
        String arthas = "arthas-boot.jar";
        long intervalMs = 5000;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            } else if ("--server".equals(a) && i + 1 < args.length) {
                server = args[++i];
            } else if ("--token".equals(a) && i + 1 < args.length) {
                token = args[++i];
            } else if ("--name".equals(a) && i + 1 < args.length) {
                name = args[++i];
            } else if ("--arthas".equals(a) && i + 1 < args.length) {
                arthas = args[++i];
            } else if ("--interval".equals(a) && i + 1 < args.length) {
                intervalMs = Long.parseLong(args[++i]) * 1000L;
            } else {
                System.err.println("未知或不完整参数: " + a);
                printUsage();
                System.exit(2);
            }
        }
        String envServer = System.getenv("BIANQUE_SERVER");
        if ((server == null || server.isBlank()) && envServer != null) {
            server = envServer;
        }
        String envToken = System.getenv("BIANQUE_AGENT_TOKEN");
        if (token.isBlank() && envToken != null) {
            token = envToken;
        }
        if (server == null || server.isBlank()) {
            System.err.println("缺少 --server（或环境变量 BIANQUE_SERVER）");
            printUsage();
            System.exit(2);
        }
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }

        String hostname = name;
        String hostIp = "127.0.0.1";
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (hostname == null) {
                hostname = local.getHostName();
            }
            String addr = local.getHostAddress();
            if (addr != null && !addr.isBlank()) {
                hostIp = addr;
            }
        } catch (Exception e) {
            if (hostname == null) {
                hostname = "unknown-host";
            }
        }
        String os = System.getProperty("os.name") + " " + System.getProperty("os.version");
        String javaVersion = System.getProperty("java.version");

        String javaPath = System.getProperty("java.home") + File.separator + "bin" + File.separator
                + (ProcScanner.isWindows() ? "java.exe" : "java");
        ArthasRunner runner = new ArthasRunner(arthas, javaPath);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bq-agent-worker");
            t.setDaemon(true);
            return t;
        });

        log("扁鹊平台 Agent " + VERSION + " 启动，平台 " + server + "，主机 " + hostname + "(" + hostIp + ")");

        // 注册：agentId 持久化（bq-agent.id），跨重启身份稳定；服务端删除后重注册沿用同一身份
        String agentId = loadId();
        Map<String, Object> regBody = MiniJson.obj();
        regBody.put("agentVersion", VERSION);
        regBody.put("host", hostIp);
        regBody.put("hostname", hostname);
        regBody.put("os", os);
        regBody.put("javaVersion", javaVersion);
        long[] intervalRef = {intervalMs};
        agentId = register(http, server, token, regBody, agentId, intervalRef);
        log("注册成功 agentId=" + agentId);

        long failures = 0;
        while (true) {
            try {
                String resp = post(http, server, token, "/api/agent/heartbeat",
                        MiniJson.write(heartbeatBody(agentId, hostIp)));
                failures = 0;
                Map<?, ?> hb = (Map<?, ?>) MiniJson.parse(resp);
                applyInterval(hb, intervalRef);
                Object cmd = hb.get("command");
                if (cmd instanceof Map) {
                    dispatch(worker, runner, http, server, token, (Map<?, ?>) cmd);
                }
            } catch (HttpFail e) {
                if (e.code == 410) {
                    // 节点在服务端被清理（离线删除）：重新登记恢复同一身份，心跳随之恢复
                    log("节点已被服务端清理（410），重新注册…");
                    agentId = register(http, server, token, regBody, agentId, intervalRef);
                    log("重新注册成功 agentId=" + agentId);
                    failures = 0;
                } else {
                    failures++;
                    if (failures == 1 || failures % 12 == 0) {
                        log("心跳失败（连续 " + failures + " 次）: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                failures++;
                if (failures == 1 || failures % 12 == 0) {
                    log("心跳失败（连续 " + failures + " 次）: " + e.getMessage());
                }
            }
            sleep(intervalRef[0]);
        }
    }

    /** 命令单执行（单线程 worker 串行）；mainClass 由进程清单反查 */
    private static void dispatch(ExecutorService worker, ArthasRunner runner, HttpClient http,
                                 String server, String token, Map<?, ?> cmd) {
        final long id = ((Number) cmd.get("id")).longValue();
        final String instanceId = String.valueOf(cmd.get("instanceId"));
        final String command = String.valueOf(cmd.get("command"));
        worker.submit(() -> {
            long begin = System.currentTimeMillis();
            String status;
            String output = null;
            String outputKind = "text";
            String error = null;
            try {
                long pid = Long.parseLong(instanceId.substring(instanceId.lastIndexOf('-') + 1));
                String mainClass = "unknown";
                for (ProcScanner.PInfo p : ProcScanner.scan()) {
                    if (p.pid == pid) {
                        mainClass = p.mainClass;
                        break;
                    }
                }
                ArthasRunner.ExecResult r = runner.exec(pid, mainClass, command);
                status = r.error == null ? "DONE" : "FAILED";
                output = r.output;
                outputKind = r.outputKind != null ? r.outputKind : "text";
                error = r.error;
            } catch (Exception e) {
                status = "FAILED";
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long cost = System.currentTimeMillis() - begin;
            Map<String, Object> payload = MiniJson.obj();
            payload.put("status", status);
            payload.put("output", output);
            payload.put("outputKind", outputKind);
            payload.put("error", error);
            payload.put("costMs", cost);
            try {
                post(http, server, token, "/api/agent/commands/" + id + "/result", MiniJson.write(payload));
                log("命令单 " + id + " 完成（" + command + "，" + cost + "ms，" + status + "）");
            } catch (Exception e) {
                log("结果回传失败（命令单 " + id + "）: " + e.getMessage());
            }
        });
    }

    private static Map<String, Object> heartbeatBody(String agentId, String hostIp) {
        List<Map<String, Object>> procs = new ArrayList<>();
        for (ProcScanner.PInfo p : ProcScanner.scan()) {
            Map<String, Object> m = MiniJson.obj();
            m.put("pid", p.pid);
            m.put("mainClass", p.mainClass);
            procs.add(m);
        }
        Map<String, Object> body = MiniJson.obj();
        body.put("agentId", agentId);
        body.put("host", hostIp);
        body.put("processes", procs);
        return body;
    }

    /** 注册（带重试直至成功）：agentId 尽量沿用（服务端按 agentId 幂等登记）并持久化 */
    private static String register(HttpClient http, String server, String token,
                                   Map<String, Object> regBody, String agentId, long[] intervalRef) {
        while (true) {
            try {
                if (agentId != null) {
                    regBody.put("agentId", agentId);
                }
                String resp = post(http, server, token, "/api/agent/register", MiniJson.write(regBody));
                Map<?, ?> body = (Map<?, ?>) MiniJson.parse(resp);
                applyInterval(body, intervalRef);
                Object id = body.get("agentId");
                if (id != null) {
                    saveId(String.valueOf(id));
                    return String.valueOf(id);
                }
            } catch (Exception e) {
                log("注册失败，5s 后重试: " + e.getMessage());
            }
            sleep(5000);
        }
    }

    /** 心跳节奏跟随服务端下发（参数中心 agent.heartbeat-interval-seconds），夹取 2~60s；缺省保持现值 */
    private static void applyInterval(Map<?, ?> resp, long[] intervalRef) {
        Object v = resp == null ? null : resp.get("heartbeatIntervalSeconds");
        if (v instanceof Number n) {
            long sec = Math.max(2, Math.min(60, n.longValue()));
            if (sec * 1000L != intervalRef[0]) {
                intervalRef[0] = sec * 1000L;
                log("心跳间隔调整为 " + sec + "s（服务端下发）");
            }
        }
    }

    /** 非 2xx 响应：携带状态码（410 = 节点已被服务端清理，需重新注册） */
    private static class HttpFail extends RuntimeException {
        final int code;

        HttpFail(int code, String body) {
            super("HTTP " + code + ": " + body);
            this.code = code;
        }
    }

    private static String post(HttpClient http, String server, String token, String path, String json)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(server + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (token != null && !token.isBlank()) {
            b.header("X-Agent-Token", token);
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new HttpFail(resp.statusCode(),
                    resp.body().substring(0, Math.min(resp.body().length(), 200)));
        }
        return resp.body();
    }

    private static String loadId() {
        try {
            if (Files.exists(ID_FILE)) {
                String id = new String(Files.readAllBytes(ID_FILE), StandardCharsets.UTF_8).trim();
                return id.isEmpty() ? null : id;
            }
        } catch (Exception ignore) {
            // 读不到就当新节点注册
        }
        return null;
    }

    private static void saveId(String id) {
        try {
            Files.write(ID_FILE, id.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log("agentId 持久化失败（重启后将作为新节点注册）: " + e.getMessage());
        }
    }

    static void log(String msg) {
        System.out.println("[" + java.time.LocalDateTime.now().toLocalTime() + "] " + msg);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void printUsage() {
        System.out.println("扁鹊平台 Agent " + VERSION);
        System.out.println("用法: java -jar bq-agent.jar --server http://平台:8080 [选项]");
        System.out.println("  --server <url>    平台地址（或环境变量 BIANQUE_SERVER）");
        System.out.println("  --token <token>   接入令牌 bianque.agent.token（或 BIANQUE_AGENT_TOKEN）");
        System.out.println("  --arthas <path>   arthas-boot.jar 路径（默认当前目录；同目录存在 arthas-core.jar");
        System.out.println("                    即完整发行目录时自动 --arthas-home 使用，离线可用且支持跨用户 attach）");
        System.out.println("  --name <host>     节点显示名（默认取 hostname）");
        System.out.println("  --interval <sec>  心跳间隔秒数（默认 5）");
    }
}
