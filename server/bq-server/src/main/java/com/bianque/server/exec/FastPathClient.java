package com.bianque.server.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * arthas agent 常驻通道客户端：直连目标进程 agent 的 HTTP 端口（默认 8563）执行命令。
 * 纯 HTTP 零子进程（毫秒级），返回控制台风格的文本渲染结果；不支持的类型返回 JSON 原文。
 * 注意：文本渲染在客户端完成（agent 只回结构化模型），这里实现平台常用命令的渲染。
 */
public class FastPathClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final Duration execTimeout;

    public FastPathClient(Duration execTimeout) {
        this.execTimeout = execTimeout;
    }

    /** 探测端口是否为存活 arthas agent */
    public boolean probe(int httpPort) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + httpPort + "/api"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"action\":\"exec\",\"command\":\"version\"}")).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("version");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 执行命令并渲染为控制台风格文本。
     * @return DiagResult 风格的 [text, error]，error 非 null 表示快路径失败（调用方应回落）
     */
    public ExecOut exec(int httpPort, String command) {
        try {
            String payload = JSON.writeValueAsString(java.util.Map.of("action", "exec", "command", command));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + httpPort + "/api"))
                    .timeout(execTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new ExecOut(null, "HTTP " + resp.statusCode());
            }
            JsonNode root = JSON.readTree(resp.body());
            JsonNode results = root.path("body").path("results");
            if (!results.isArray() || results.isEmpty()) {
                return new ExecOut(null, "空结果");
            }
            return new ExecOut(renderResults(results), null);
        } catch (Exception e) {
            return new ExecOut(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 停止目标 agent（等价控制台 stop：卸载增强，防残留） */
    public boolean stop(int httpPort) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + httpPort + "/api"))
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"action\":\"exec\",\"command\":\"stop\"}")).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- 渲染：agent 返回结构化模型，按平台常用命令类型转控制台风格文本 ----

    /** 渲染 arthas agent /api 的原始响应 JSON（平台 Agent 通道回传形态）；解析失败返回 null */
    public static String renderAgentJson(String body) {
        try {
            JsonNode results = JSON.readTree(body).path("body").path("results");
            return results.isArray() && !results.isEmpty() ? renderResults(results) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String renderResults(JsonNode results) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode r : results) {
            String type = r.path("type").asText("");
            switch (type) {
                case "version" -> sb.append("version: ").append(r.path("version").asText()).append('\n');
                case "jvm" -> renderJvm(sb, r.path("jvmInfo"));
                case "memory" -> renderMemory(sb, r.path("memoryInfo"));
                case "thread" -> renderThread(sb, r);
                case "status" -> { /* 终止标记，忽略 */ }
                default -> {
                    sb.append(type).append(": ").append(r.toString()).append('\n');
                }
            }
        }
        return sb.toString().trim();
    }

    private static void renderJvm(StringBuilder sb, JsonNode jvmInfo) {
        if (!jvmInfo.isObject()) {
            return;
        }
        jvmInfo.fieldNames().forEachRemaining(section -> {
            sb.append(section).append('\n');
            for (JsonNode kv : jvmInfo.path(section)) {
                sb.append(' ').append(kv.path("name").asText())
                        .append(" : ").append(kv.path("value").asText()).append('\n');
            }
        });
    }

    private static void renderMemory(StringBuilder sb, JsonNode mi) {
        if (!mi.isObject()) {
            return;
        }
        List<String> sections = List.of("heap", "nonheap");
        for (String sec : sections) {
            sb.append(sec).append('\n');
            for (JsonNode m : mi.path(sec)) {
                sb.append(' ').append(String.format("%-22s used=%-12d total=%-12d max=%d%n",
                        m.path("name").asText(), m.path("used").asLong(),
                        m.path("total").asLong(), m.path("max").asLong()));
            }
        }
    }

    private static void renderThread(StringBuilder sb, JsonNode r) {
        JsonNode stats = r.path("threadStateCount");
        if (stats.isObject()) {
            StringBuilder line = new StringBuilder("线程总数: ");
            stats.fieldNames().forEachRemaining(st ->
                    line.append(st).append('=').append(stats.path(st).asInt()).append(' '));
            sb.append(line.toString().trim()).append('\n');
        }
        JsonNode list = r.path("busyThreads").isArray() && !r.path("busyThreads").isEmpty()
                ? r.path("busyThreads") : r.path("threadStats");
        for (JsonNode t : list) {
            sb.append(String.format("\"%s\" Id=%d cpuUsage=%.1f%% %s%n",
                    t.path("name").asText(), t.path("id").asInt(),
                    t.path("cpu").asDouble(), t.path("state").asText()));
            for (JsonNode fr : t.path("stackTrace")) {
                sb.append("    at ").append(fr.path("className").asText()).append('.')
                        .append(fr.path("methodName").asText())
                        .append('(').append(fr.path("fileName").asText())
                        .append(':').append(fr.path("lineNumber").asInt()).append(")\n");
            }
            sb.append('\n');
        }
    }

    public record ExecOut(String output, String error) {
    }
}
