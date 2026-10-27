package com.bianque.server.service;

import com.bianque.server.model.Types.Instance;
import com.bianque.server.repo.FleetRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 文档中心：读取构建期产物（manifest + search-index），提供目录、搜索与静态访问路径。
 */
@Service
public class DocsService {

    public record DocHit(String slug, String title, String category, String risk,
                         String path, String snippet) {
    }

    private final Path unpackRoot;
    private final List<DocHit> index = new ArrayList<>();

    public DocsService(FleetRepository fleet,
                       @Value("${bianque.docs.unpack-root:}") String unpackRoot) {
        this.unpackRoot = resolveUnpackRoot(unpackRoot);
        reload();
    }

    /** 兼容多工作目录：显式配置优先，否则按常见运行目录探测 */
    public static Path resolveUnpackRoot(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        for (String candidate : new String[]{
                "../docs/arthas-docs/4.3.5", "../../docs/arthas-docs/4.3.5",
                "docs/arthas-docs/4.3.5", "./docs/arthas-docs/4.3.5"}) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Path.of("../docs/arthas-docs/4.3.5");
    }

    /** 从磁盘重建索引（doc zip 更新后可调用来刷新） */
    public synchronized int reload() {
        index.clear();
        Path docDir = unpackRoot.resolve("doc");
        if (!Files.isDirectory(docDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(docDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".html"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String slug = p.getFileName().toString().replace(".html", "");
                        index.add(new DocHit(slug, prettify(slug), categorize(slug), riskOf(slug),
                                "doc/" + slug + ".html", excerpt(p)));
                    });
        } catch (IOException e) {
            return index.size();
        }
        return index.size();
    }

    public List<DocHit> catalog() {
        return List.copyOf(index);
    }

    public List<DocHit> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) {
            return List.of();
        }
        return index.stream()
                .map(d -> score(d, q))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(limit <= 0 ? 20 : limit)
                .map(s -> s.hit())
                .toList();
    }

    public Path resolvePage(String relativePath) {
        Path root = unpackRoot.toAbsolutePath().normalize();
        Path normalized = root.resolve(relativePath).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("非法路径");
        }
        return normalized;
    }

    private record Scored(DocHit hit, int score) {
    }

    private Scored score(DocHit d, String q) {
        int s = 0;
        if (d.slug().equalsIgnoreCase(q)) {
            s += 100;
        }
        if (d.slug().toLowerCase().contains(q)) {
            s += 50;
        }
        if (d.title().toLowerCase().contains(q)) {
            s += 40;
        }
        if (d.snippet().toLowerCase().contains(q)) {
            s += 10;
        }
        return new Scored(d, s);
    }

    private static String prettify(String slug) {
        return switch (slug) {
            case "index" -> "简介";
            case "quick-start" -> "快速入门";
            case "commands" -> "命令列表";
            case "mcp-server" -> "Arthas MCP Server";
            case "arthas-properties" -> "Arthas Properties";
            case "http-api" -> "Http API";
            case "web-console" -> "Web Console";
            case "tunnel" -> "Arthas Tunnel";
            case "advanced-use" -> "其他特性";
            case "batch-support" -> "批处理功能";
            case "external-command" -> "加载外部命令";
            case "advice-class" -> "表达式核心变量";
            case "ognl-express" -> "OGNL 复杂表达式";
            case "spring-boot-starter" -> "Spring Boot Starter";
            case "release-notes" -> "Release Notes";
            case "contact-us" -> "联系我们";
            case "arthas3" -> "Arthas 3.x 说明";
            default -> slug;
        };
    }

    private static String categorize(String slug) {
        return switch (slug) {
            case "index", "quick-start", "start-arthas", "install-detail", "manual-install",
                    "download", "arthas-tutorials", "release-notes", "faq", "contact-us", "arthas3" -> "入门";
            case "classloader", "classloader-metaspace", "dump", "jad", "mc",
                    "redefine", "retransform", "sc", "sm" -> "类与 ClassLoader";
            case "monitor", "line", "stack", "trace", "tt", "watch" -> "方法观测";
            case "profiler", "jfr" -> "性能剖析";
            case "auth", "options" -> "鉴权与开关";
            case "base64", "cat", "cls", "echo", "grep", "help", "history", "keymap",
                    "pwd", "quit", "reset", "session", "stop", "tee", "version" -> "基础命令";
            case "advice-class", "ognl-express" -> "表达式与技巧";
            case "advanced-use", "async", "save-log", "docker", "web-console",
                    "idea-plugin", "commands" -> "平台特性";
            case "agent", "spring-boot-starter", "external-command", "http-api", "batch-support",
                    "tunnel", "arthas-properties", "mcp-server", "ai", "groovy" -> "集成与扩展";
            default -> "JVM 相关";
        };
    }

    private static String riskOf(String slug) {
        if ("ognl,redefine,retransform,mc,vmoption,sysprop,logger,vmtool,stop,heapdump,groovy".contains(slug)
                && List.of("ognl", "redefine", "retransform", "mc", "vmoption", "sysprop", "logger",
                        "vmtool", "stop", "heapdump", "groovy").contains(slug)) {
            return "high";
        }
        if (List.of("watch", "trace", "tt", "monitor", "stack", "line", "profiler", "jfr").contains(slug)) {
            return "mid";
        }
        return "low";
    }

    private static String excerpt(Path page) {
        try {
            String src = Files.readString(page);
            src = src.replaceAll("(?s)<script.*?</script>", " ");
            src = src.replaceAll("(?s)<style.*?</style>", " ");
            src = src.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            return src.length() > 3000 ? src.substring(0, 3000) : src;
        } catch (IOException e) {
            return "";
        }
    }
}
