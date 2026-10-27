package com.bianque.server.exec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本机 Java 进程扫描（jps -l）：纳管 attach 的目标发现。
 * 输出行格式：<pid> <主类或jar路径>；首行为表头时跳过。
 */
@Component
public class LocalProcessScanner {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessScanner.class);
    private static final Pattern LINE = Pattern.compile("^(\\d+)\\s+(\\S.*)$");

    private final String jpsCommand;

    public LocalProcessScanner(@Value("${bianque.execution.jps-command:jps}") String jpsCommand) {
        this.jpsCommand = jpsCommand;
    }

    public record LocalProcess(long pid, String mainClass) {
    }

    public List<LocalProcess> scan() {
        List<LocalProcess> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(jpsCommand.split("\\s+")).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = LINE.matcher(line.trim());
                    if (m.matches()) {
                        out.add(new LocalProcess(Long.parseLong(m.group(1)), m.group(2)));
                    }
                }
            }
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("jps 扫描失败: {}", e.getMessage());
        }
        return out;
    }

    /** 解析测试：暴露为静态纯函数 */
    static List<LocalProcess> parse(List<String> lines) {
        List<LocalProcess> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = LINE.matcher(line.trim());
            if (m.matches()) {
                out.add(new LocalProcess(Long.parseLong(m.group(1)), m.group(2)));
            }
        }
        return out;
    }
}
