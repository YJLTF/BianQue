package com.bianque.core.diag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断命令结果结构化解析：供图表可视化与双实例对比采样使用。
 * 同时兼容 Arthas 4.3.5 官方输出与平台拟真通道输出；解析失败由调用方降级纯文本视图，
 * 文本形态永远是兜底视图。
 */
public final class ResultParsers {

    private ResultParsers() {
    }

    // ---- 数据模型 ----

    /** 单线程：id=-1 表示 JVM 内部线程（官方 thread 表格形态） */
    public record ThreadInfo(String name, long id, double cpuPct, String state) {
    }

    public record ThreadView(Integer total, Map<String, Long> stateCounts, List<ThreadInfo> topCpu) {
    }

    /** 内存池：usagePct 为 0~100；max 未知时为 -1 */
    public record MemoryPool(String name, double usedBytes, double maxBytes, double usagePct) {
    }

    public record MemoryView(List<MemoryPool> pools, Double heapPct, Double oldPct, Double metaPct, Double cpuPct) {
    }

    public record MonitorPoint(String ts, String cls, String method, long total, long success,
                               long fail, double avgRtMs, double failRatePct) {
    }

    /** jvm 键值（含 gc.*_count 等）；gcTotalCount 为全部 GC 次数求和（缺省 -1） */
    public record JvmView(Map<String, String> kv, long gcTotalCount) {
    }

    // ---- 解析入口 ----

    /**
     * 按命令首 token 分派；无结构化结果返回 null（前端回退文本视图）。
     * dashboard 单命令同时产出 thread 摘要与内存水位。
     */
    public static Map<String, Object> parse(String commandText, String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String cmd = commandText == null ? "" : commandText.trim().split("\\s+")[0].toLowerCase();
        Map<String, Object> out = new LinkedHashMap<>();
        switch (cmd) {
            case "thread" -> {
                ThreadView v = parseThread(output);
                if (v != null) {
                    out.put("kind", "thread");
                    out.put("thread", v);
                    return out;
                }
            }
            case "memory" -> {
                MemoryView v = parseMemory(output);
                if (v != null) {
                    out.put("kind", "memory");
                    out.put("memory", v);
                    return out;
                }
            }
            case "jvm" -> {
                JvmView v = parseJvm(output);
                if (v != null) {
                    out.put("kind", "jvm");
                    out.put("jvm", v);
                    return out;
                }
            }
            case "dashboard" -> {
                ThreadView t = parseThread(output);
                MemoryView m = parseMemory(output);
                if (t != null || m != null) {
                    out.put("kind", "dashboard");
                    if (t != null) {
                        out.put("thread", t);
                    }
                    if (m != null) {
                        out.put("memory", m);
                    }
                    return out;
                }
            }
            case "monitor" -> {
                List<MonitorPoint> pts = parseMonitor(output);
                if (!pts.isEmpty()) {
                    out.put("kind", "monitor");
                    out.put("monitor", pts);
                    return out;
                }
            }
            default -> {
                return null;
            }
        }
        return null;
    }

    // ---- thread ----

    private static final Pattern STATE_WORD =
            Pattern.compile("\\b(NEW|RUNNABLE|BLOCKED|WAITING|TIMED_WAITING|TERMINATED)\\b");
    private static final Pattern TABLE_SUMMARY = Pattern.compile(
            "Threads?\\s+Total:\\s*(\\d+)((?:,\\s*[A-Z_]+:\\s*\\d+)+)");
    private static final Pattern TABLE_STATE_PAIR = Pattern.compile("([A-Z_]+):\\s*(\\d+)");
    // ID NAME GROUP PRIORITY STATE %CPU（GROUP/PRIORITY 显式成组，防名字吞列）
    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\s*(-?\\d+)\\s+(\\S.*?)\\s+(\\S+)\\s+(-?\\d+)\\s+(NEW|RUNNABLE|BLOCKED|WAITING|TIMED_WAITING|TERMINATED|-)\\s+([\\d.]+|-)\\s",
            Pattern.MULTILINE);
    private static final Pattern TOP_HEADER = Pattern.compile(
            "\"([^\"]+)\"\\s+(?:\\[Internal]\\s+)?(?:Id=(\\d+)\\s+)?cpuUsage=([\\d.]+)%");
    private static final Pattern MOCK_THREAD_LINE = Pattern.compile(
            "\"([^\"]+)\"\\s+Id=(\\d+)\\s+CPU=([\\d.]+)%\\s+([A-Z_]+)");
    private static final Pattern MOCK_TOTAL_LINE = Pattern.compile(
            "线程数\\s*=\\s*(\\d+)|Thread:\\s*total\\s*(\\d+)((?:\\s+[A-Z_]+\\s+\\d+)*)");

    /**
     * 兼容三种形态：官方表格（状态汇总行 + 明细行）、官方 -n top 块、拟真通道格式。
     * 汇总行存在时状态分布以汇总行为准（明细行只是子集，不重复计数）。
     */
    public static ThreadView parseThread(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Integer total = null;
        Map<String, Long> states = new LinkedHashMap<>();
        List<ThreadInfo> top = new ArrayList<>();
        boolean authoritative = false;

        Matcher m = TABLE_SUMMARY.matcher(output);
        if (m.find()) {
            total = Integer.parseInt(m.group(1));
            Matcher ps = TABLE_STATE_PAIR.matcher(m.group(2));
            while (ps.find()) {
                states.put(ps.group(1), Long.parseLong(ps.group(2)));
            }
            authoritative = true;
        }
        // 官方表格行（ID NAME GROUP PRIORITY STATE %CPU …）；ID=-1 为内部线程
        Matcher row = TABLE_ROW.matcher(output);
        while (row.find()) {
            double cpu = "-".equals(row.group(6)) ? 0 : Double.parseDouble(row.group(6));
            String st = "-".equals(row.group(5)) ? "" : row.group(5);
            top.add(new ThreadInfo(row.group(2).trim(), Long.parseLong(row.group(1)), cpu, st));
            if (!authoritative) {
                states.merge(st.isEmpty() ? "INTERNAL" : st, 1L, Long::sum);
            }
        }
        // 官方 -n top 形态：头部行逐块（状态从本行头部之后找，栈行不参与）
        if (top.isEmpty()) {
            Matcher h = TOP_HEADER.matcher(output);
            while (h.find()) {
                int lineEnd = output.indexOf('\n', h.start());
                if (lineEnd < 0) {
                    lineEnd = output.length();
                }
                String rest = output.substring(Math.min(h.end(), lineEnd), lineEnd);
                Matcher st = STATE_WORD.matcher(rest);
                String state = st.find() ? st.group(1) : "";
                top.add(new ThreadInfo(h.group(1), h.group(2) == null ? -1 : Long.parseLong(h.group(2)),
                        Double.parseDouble(h.group(3)), state));
            }
        }
        // 拟真通道："name" Id=142 CPU=80.8% WAITING on …
        if (top.isEmpty()) {
            Matcher ml = MOCK_THREAD_LINE.matcher(output);
            while (ml.find()) {
                top.add(new ThreadInfo(ml.group(1), Long.parseLong(ml.group(2)),
                        Double.parseDouble(ml.group(3)), ml.group(4)));
            }
        }
        if (total == null) {
            Matcher mt = MOCK_TOTAL_LINE.matcher(output);
            if (mt.find()) {
                total = Integer.parseInt(mt.group(1) != null ? mt.group(1) : mt.group(2));
                if (!authoritative && mt.group(3) != null && !mt.group(3).isBlank()) {
                    Matcher pair = Pattern.compile("([A-Z_]+)\\s+(\\d+)").matcher(mt.group(3));
                    while (pair.find()) {
                        states.merge(pair.group(1), Long.parseLong(pair.group(2)), Long::sum);
                    }
                }
            }
        }
        if (total == null && top.isEmpty() && states.isEmpty()) {
            return null; // 完全不可识别：调用方回退文本
        }
        if (total == null && !states.isEmpty()) {
            total = (int) states.values().stream().mapToLong(Long::longValue).sum();
        }
        // 状态分布兜底：从 top 列表聚合
        if (states.isEmpty() && !top.isEmpty()) {
            for (ThreadInfo t : top) {
                states.merge(t.state().isEmpty() ? "UNKNOWN" : t.state(), 1L, Long::sum);
            }
        }
        top.sort((a, b) -> Double.compare(b.cpuPct(), a.cpuPct()));
        if (top.size() > 10) {
            top = new ArrayList<>(top.subList(0, 10));
        }
        return new ThreadView(total, states, top);
    }

    // ---- memory ----

    private static final Pattern MEM_TABLE_ROW = Pattern.compile(
            "^\\s*([A-Za-z][\\w'\\-. ]*?)\\s{2,}(\\d+(?:\\.\\d+)?[KMGTP]?B?)\\s+(\\d+(?:\\.\\d+)?[KMGTP]?B?|-)\\s+"
                    + "(\\d+(?:\\.\\d+)?[KMGTP]?B?|-1|-)\\s+(-?[\\d.]+)%?\\s*$",
            Pattern.MULTILINE);
    private static final Pattern MOCK_KV = Pattern.compile(
            "^\\s*(heap|old\\.gen|metaspace)\\.usage\\s*=\\s*([\\d.]+)%\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern MOCK_SIZE = Pattern.compile(
            "^\\s*(eden|old|meta)\\s*:\\s*(\\d+)MB used / (\\d+)MB",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern DASH_MEMORY = Pattern.compile(
            "Memory:\\s*heap\\s*([\\d.]+)%\\s*old\\s*([\\d.]+)%\\s*meta\\s*([\\d.]+)%");

    /** 官方表格 / 拟真键值 / dashboard 单行 三种形态 */
    public static MemoryView parseMemory(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        List<MemoryPool> pools = new ArrayList<>();
        Double heap = null, old = null, meta = null, cpu = null;

        Matcher d = DASH_MEMORY.matcher(output);
        if (d.find()) {
            heap = Double.parseDouble(d.group(1));
            old = Double.parseDouble(d.group(2));
            meta = Double.parseDouble(d.group(3));
            Matcher c = Pattern.compile("CPU:\\s*([\\d.]+)%").matcher(output);
            if (c.find()) {
                cpu = Double.parseDouble(c.group(1));
            }
            pools.add(new MemoryPool("heap", -1, -1, heap));
            pools.add(new MemoryPool("old_gen", -1, -1, old));
            pools.add(new MemoryPool("metaspace", -1, -1, meta));
            return new MemoryView(pools, heap, old, meta, cpu);
        }
        Matcher kv = MOCK_KV.matcher(output);
        Matcher sz = MOCK_SIZE.matcher(output);
        if (kv.find()) {
            do {
                String name = kv.group(1).toLowerCase().replace(".gen", "_gen");
                double pct = Double.parseDouble(kv.group(2));
                pools.add(new MemoryPool(name, -1, -1, pct));
                if ("heap".equals(name)) {
                    heap = pct;
                } else if ("old_gen".equals(name)) {
                    old = pct;
                } else {
                    meta = pct;
                }
            } while (kv.find());
            while (sz.find()) {
                double used = Double.parseDouble(sz.group(2)) * 1024 * 1024;
                double max = Double.parseDouble(sz.group(3)) * 1024 * 1024;
                String name = sz.group(1).toLowerCase();
                double pct = max > 0 ? used / max * 100 : -1;
                pools.add(new MemoryPool(name, used, max, pct));
                if ("old".equals(name) && old == null) {
                    old = pct;
                }
                if ("meta".equals(name) && meta == null) {
                    meta = pct;
                }
            }
            return pools.isEmpty() ? null : new MemoryView(pools, heap, old, meta, cpu);
        }
        while (sz.find()) {
            double used = Double.parseDouble(sz.group(2)) * 1024 * 1024;
            double max = Double.parseDouble(sz.group(3)) * 1024 * 1024;
            String name = sz.group(1).toLowerCase();
            double pct = max > 0 ? used / max * 100 : -1;
            pools.add(new MemoryPool(name, used, max, pct));
            if ("old".equals(name)) {
                old = pct;
            }
            if ("meta".equals(name)) {
                meta = pct;
            }
        }
        if (!pools.isEmpty()) {
            return new MemoryView(pools, heap, old, meta, cpu);
        }
        Matcher row = MEM_TABLE_ROW.matcher(output);
        int hits = 0;
        while (row.find()) {
            hits++;
            String name = row.group(1).trim();
            double used = parseSize(row.group(2));
            double max = "-".equals(row.group(4)) || "-1".equals(row.group(4)) ? -1 : parseSize(row.group(4));
            double pct = Double.parseDouble(row.group(5));
            if (pct <= 0 && max > 0) {
                pct = used / max * 100;
            }
            pools.add(new MemoryPool(name, used, max, pct));
            if (name.equals("heap")) {
                heap = pct;
            }
            if (name.contains("old")) {
                old = pct;
            }
            if (name.contains("metaspace")) {
                meta = pct;
            }
        }
        if (hits >= 3) {
            return new MemoryView(pools, heap, old, meta, cpu);
        }
        return null;
    }

    /** 32M / 685K / 1G / 4096M / 120032K → 字节数 */
    static double parseSize(String s) {
        s = s.trim();
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)?)([KMGTP]?)(?:B?)$").matcher(s);
        if (!m.matches()) {
            return -1;
        }
        double v = Double.parseDouble(m.group(1));
        return switch (m.group(2).isEmpty() ? "B" : m.group(2)) {
            case "K" -> v * 1024;
            case "M" -> v * 1024 * 1024;
            case "G" -> v * 1024 * 1024 * 1024;
            case "T" -> v * 1024L * 1024 * 1024 * 1024;
            case "P" -> v * 1024L * 1024 * 1024 * 1024 * 1024;
            default -> v;
        };
    }

    // ---- jvm ----

    /** key= value（拟真）/ KEY  VALUE 两格对齐（官方）双形态；单空格的栈行（at …）不匹配 */
    private static final Pattern JVM_KV = Pattern.compile(
            "^\\s*([A-Za-z][\\w.\\-]*)\\s*(?:=\\s+|\\s{2,})(\\S.*?)\\s*$", Pattern.MULTILINE);

    public static JvmView parseJvm(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : output.split("\\r?\\n")) {
            Matcher m = JVM_KV.matcher(line);
            if (!m.find()) {
                continue;
            }
            String key = m.group(1);
            String val = m.group(2).trim();
            if (val.isEmpty() || key.matches("RUNTIME|MEMORY|GC|CLASS-LOADING|COMPILATION|OPERATING-SYSTEM|MEMORY-MANAGEMENT|THREAD")) {
                continue;
            }
            kv.put(key, val);
        }
        if (kv.isEmpty()) {
            return null;
        }
        long gc = kv.entrySet().stream()
                .filter(e -> e.getKey().matches("gc\\.[\\w.\\-]*count"))
                .mapToLong(e -> parseLongSafe(e.getValue()))
                .sum();
        return new JvmView(kv, gc);
    }

    private static long parseLongSafe(String s) {
        try {
            return Math.round(Double.parseDouble(s.replaceAll("[^0-9.]", "")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- monitor ----

    private static final Pattern MONITOR_ROW = Pattern.compile(
            "^\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+"
                    + "([\\d.]+)\\s+([\\d.]+)%\\s*$",
            Pattern.MULTILINE);

    public static List<MonitorPoint> parseMonitor(String output) {
        List<MonitorPoint> pts = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return pts;
        }
        Matcher m = MONITOR_ROW.matcher(output);
        while (m.find()) {
            pts.add(new MonitorPoint(m.group(1), m.group(2), m.group(3),
                    Long.parseLong(m.group(4)), Long.parseLong(m.group(5)), Long.parseLong(m.group(6)),
                    Double.parseDouble(m.group(7)), Double.parseDouble(m.group(8))));
        }
        return pts;
    }

    /** 供统计对比使用：把输出解析为标量指标（仅收录语义明确的百分比/计数） */
    public static Map<String, Double> scalarMetrics(String commandText, String output) {
        Map<String, Double> out = new LinkedHashMap<>();
        String cmd = commandText == null ? "" : commandText.trim().split("\\s+")[0].toLowerCase();
        if ("thread".equals(cmd) || "dashboard".equals(cmd)) {
            ThreadView t = parseThread(output);
            if (t != null && t.total() != null) {
                out.put("thread.total", (double) t.total());
                Long waiting = t.stateCounts().get("WAITING");
                if (waiting != null) {
                    out.put("thread.waiting", (double) waiting);
                }
            }
        }
        if ("memory".equals(cmd) || "dashboard".equals(cmd)) {
            MemoryView m = parseMemory(output);
            if (m != null) {
                if (m.heapPct() != null) {
                    out.put("mem.heap.pct", m.heapPct());
                }
                if (m.oldPct() != null) {
                    out.put("mem.old.pct", m.oldPct());
                }
                if (m.metaPct() != null) {
                    out.put("mem.meta.pct", m.metaPct());
                }
            }
        }
        if ("dashboard".equals(cmd)) {
            Matcher c = Pattern.compile("CPU:\\s*([\\d.]+)%").matcher(output);
            if (c.find()) {
                out.put("cpu.pct", Double.parseDouble(c.group(1)));
            }
        }
        if ("jvm".equals(cmd)) {
            JvmView j = parseJvm(output);
            if (j != null) {
                if (j.gcTotalCount() >= 0) {
                    out.put("gc.total.count", (double) j.gcTotalCount());
                }
                j.kv().forEach((k, v) -> {
                    if (k.matches("gc\\.[\\w.\\-]*count")) {
                        out.put(k, (double) parseLongSafe(v));
                    }
                });
                String threads = j.kv().get("thread.count");
                if (threads != null) {
                    out.put("thread.total", (double) parseLongSafe(threads));
                }
            }
        }
        return out;
    }

    /** Welch t 检验（双侧 p 值，t 分布正态近似；样本 ≥2 才有效）——火山图双实例对比用 */
    public static double welchPValue(double[] a, double[] b) {
        int n1 = a.length, n2 = b.length;
        if (n1 < 2 || n2 < 2) {
            return 1.0;
        }
        double m1 = Arrays.stream(a).average().orElse(0);
        double m2 = Arrays.stream(b).average().orElse(0);
        double v1 = Arrays.stream(a).map(x -> (x - m1) * (x - m1)).sum() / (n1 - 1);
        double v2 = Arrays.stream(b).map(x -> (x - m2) * (x - m2)).sum() / (n2 - 1);
        if (v1 <= 0 && v2 <= 0) {
            return m1 == m2 ? 1.0 : 0.0; // 无方差：均值相同则不显著，不同则视为极显著
        }
        double se2 = v1 / n1 + v2 / n2;
        if (se2 <= 0) {
            return 1.0;
        }
        double t = (m1 - m2) / Math.sqrt(se2);
        // 正态近似（样本少且方差齐时略激进，产品口径已定位"参考性对比"）
        double p = 2 * (1 - normalCdf(Math.abs(t)));
        return Math.max(0.0, Math.min(1.0, p));
    }

    /** 标准正态分布 CDF（Zelen & Severo 近似，精度 ~1e-7） */
    static double normalCdf(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * z);
        double d = 0.3989422804014327 * Math.exp(-z * z / 2.0);
        double p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                + t * (-1.821255978 + t * 1.330274429))));
        return 1.0 - p;
    }
}
