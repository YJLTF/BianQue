package com.bianque.server.service;

import com.bianque.server.model.Types.CheckupRow;
import com.bianque.server.model.Types.Instance;
import com.bianque.server.repo.FleetRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 扁鹊·体检：巡检模板（只读命令集）→ 阈值打分 → 健康分。
 */
@Service
public class CheckupService {

    private static final List<String> TEMPLATE = List.of("jvm", "memory", "thread");

    private final DiagService diag;
    private final FleetRepository fleet;
    private final Map<String, List<CheckupRow>> latest = new ConcurrentHashMap<>();

    public CheckupService(DiagService diag, FleetRepository fleet) {
        this.diag = diag;
        this.fleet = fleet;
    }

    public List<CheckupRow> runAll() {
        List<Instance> online = fleet.allInstances().stream().filter(Instance::online).toList();
        // 虚拟线程并行巡检（JDK 21）：IO 密集，实例间互不阻塞；按主机维度限流在 S1 执行引擎统一收敛
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<CheckupRow>> futures = online.stream()
                    .map(i -> executor.submit(() -> checkOne(i)))
                    .toList();
            List<CheckupRow> rows = new ArrayList<>();
            for (var f : futures) {
                rows.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            latest.put("latest", rows);
            return rows;
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("巡检被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("巡检执行失败", e);
        }
    }

    public List<CheckupRow> latest() {
        return latest.getOrDefault("latest", List.of());
    }

    CheckupRow checkOne(Instance ins) {
        int score = ins.baseHealth();
        List<String> deductions = new ArrayList<>();
        for (String cmd : TEMPLATE) {
            String out = diag.execute(ins.instanceId(), cmd, "系统").output();
            parseDeductions(out, deductions);
        }
        for (String d : deductions) {
            score -= switch (d) {
                case "full_gc" -> 8;
                case "old_gen_high" -> 10;
                case "thread_growth" -> 6;
                case "cpu_high" -> 12;
                default -> 0;
            };
        }
        score = Math.max(30, Math.min(100, score));
        return new CheckupRow(ins.instanceId(), score, List.copyOf(deductions));
    }

    /** 从拟真/真实输出中解析指标并按阈值记扣分项 */
    static void parseDeductions(String output, List<String> deductions) {
        for (String line : output.split("\n")) {
            String[] kv = line.trim().replace("=", " ").split("\\s+");
            if (kv.length < 2) {
                continue;
            }
            try {
                int v = Integer.parseInt(kv[kv.length - 1].replace("%", "").replace(".", ""));
                String key = kv[0].toLowerCase();
                if ((key.contains("old.gen.usage") || key.contains("heap.usage")) && v >= 85) {
                    deductions.add("old_gen_high");
                }
                if (key.contains("gc.full.count") && v >= 2) {
                    deductions.add("full_gc");
                }
                if (key.startsWith("cpu") && v >= 85) {
                    deductions.add("cpu_high");
                }
                if (key.startsWith("线程总数") || key.contains("thread.count")) {
                    if (v >= 450) {
                        deductions.add("thread_growth");
                    }
                }
            } catch (NumberFormatException ignore) {
                // 非指标行
            }
        }
    }
}
