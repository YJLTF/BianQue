package com.bianque.agent;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 目标主机 JVM 进程发现：优先 attach API（JDK 9+ 内置），异常时回落 jps -l。
 * mainClass 取 displayName 首段：类名取末段，jar 去路径去后缀（与平台纳管展示一致）。
 */
public final class ProcScanner {

    /** 目标进程（pid + 归一化主类/主 jar 名） */
    public static final class PInfo {
        public final long pid;
        public final String mainClass;

        public PInfo(long pid, String mainClass) {
            this.pid = pid;
            this.mainClass = mainClass;
        }
    }

    private ProcScanner() {
    }

    public static List<PInfo> scan() {
        try {
            List<PInfo> out = new ArrayList<>();
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                try {
                    out.add(new PInfo(Long.parseLong(d.id()), normalize(d.displayName())));
                } catch (NumberFormatException ignore) {
                    // 非数字 id（理论不出现）跳过
                }
            }
            return out;
        } catch (Throwable t) {
            // attach API 不可用（精简 JRE 等）时回落 jps
            return viaJps();
        }
    }

    static String normalize(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return "unknown";
        }
        String s = displayName.trim().replace('\\', '/');
        int sp = s.indexOf(' ');
        if (sp > 0) {
            s = s.substring(0, sp); // 取命令行首段
        }
        String low = s.toLowerCase();
        if (low.endsWith(".jar")) {
            String jar = s.substring(s.lastIndexOf('/') + 1);
            return jar.substring(0, jar.length() - 4);
        }
        return s.substring(s.lastIndexOf('/') + 1);
    }

    private static List<PInfo> viaJps() {
        List<PInfo> out = new ArrayList<>();
        try {
            String jps = System.getProperty("java.home") + File.separator + "bin" + File.separator
                    + (isWindows() ? "jps.exe" : "jps");
            Process p = new ProcessBuilder(jps, "-l").start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2 && !"Jps".equals(parts[1])) {
                        try {
                            out.add(new PInfo(Long.parseLong(parts[0]), normalize(parts[1])));
                        } catch (NumberFormatException ignore) {
                            // 跳过无法解析行
                        }
                    }
                }
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignore) {
            // jps 也不可用：返回空清单，心跳照常
        }
        return out;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
