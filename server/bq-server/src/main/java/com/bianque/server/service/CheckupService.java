package com.bianque.server.service;

import com.bianque.server.model.Types.CheckupRow;
import com.bianque.server.model.Types.Instance;
import com.bianque.server.repo.FleetRepository;
import com.bianque.server.store.CheckupRunEntity;
import com.bianque.server.store.CheckupRunJpa;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 扁鹊·体检：巡检模板（只读命令集）→ 阈值打分 → 健康分。运行结果落库，重启可回溯。
 */
@Service
public class CheckupService {

    private static final Logger log = LoggerFactory.getLogger(CheckupService.class);
    private static final List<String> TEMPLATE = List.of("jvm", "memory", "thread");

    private final DiagService diag;
    private final FleetRepository fleet;
    private final CheckupRunJpa runs;
    private final ObjectMapper json;
    private final Map<String, List<CheckupRow>> latest = new ConcurrentHashMap<>();

    public CheckupService(DiagService diag, FleetRepository fleet, CheckupRunJpa runs, ObjectMapper json) {
        this.diag = diag;
        this.fleet = fleet;
        this.runs = runs;
        this.json = json;
    }

    public List<CheckupRow> runAll() {
        return runAll(DiagService.SYSTEM);
    }

    public List<CheckupRow> runAll(String operator) {
        List<Instance> online = fleet.allInstances().stream().filter(Instance::online).toList();
        // 虚拟线程并行巡检（JDK 21）：IO 密集，实例间互不阻塞。
        // 单实例失败/超时降级为扣分项，不拖垮整轮巡检（agent 节点为串行 worker，冷 attach 秒级）。
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<Instance> targets = online;
            List<java.util.concurrent.Future<CheckupRow>> futures = targets.stream()
                    .map(i -> executor.submit(() -> checkOne(i, operator)))
                    .toList();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(150);
            List<CheckupRow> rows = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                long remainMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        Math.max(1, deadline - System.nanoTime()));
                try {
                    rows.add(futures.get(i).get(Math.min(remainMs, 90_000),
                            java.util.concurrent.TimeUnit.MILLISECONDS));
                } catch (java.util.concurrent.TimeoutException te) {
                    futures.get(i).cancel(true);
                    rows.add(new CheckupRow(targets.get(i).instanceId(), 30, List.of("巡检超时")));
                } catch (java.util.concurrent.ExecutionException ee) {
                    rows.add(new CheckupRow(targets.get(i).instanceId(), 30, List.of("巡检异常")));
                }
            }
            latest.put("latest", rows);
            persist(operator, rows);
            return rows;
        } catch (java.lang.InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("巡检被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("巡检执行失败", e);
        }
    }

    public List<CheckupRow> latest() {
        if (latest.containsKey("latest")) {
            return latest.get("latest");
        }
        Optional<CheckupRunEntity> last = runs.findFirstByOrderByIdDesc();
        if (last.isEmpty()) {
            return List.of();
        }
        try {
            List<CheckupRow> rows = json.readValue(last.get().payloadJson, new TypeReference<List<CheckupRow>>() {
            });
            latest.put("latest", rows);
            return rows;
        } catch (Exception e) {
            log.warn("巡检结果反序列化失败", e);
            return List.of();
        }
    }

    /** 运行历史（最近 20 次，供趋势展示） */
    public List<Map<String, Object>> history() {
        return runs.findTop20ByOrderByIdDesc().stream()
                .map(r -> Map.<String, Object>of(
                        "runAt", r.runAt.toString(),
                        "operator", r.operator == null ? "" : r.operator,
                        "instanceCount", r.instanceCount,
                        "avgScore", r.avgScore))
                .toList();
    }

    private void persist(String operator, List<CheckupRow> rows) {
        try {
            double avg = rows.stream().mapToInt(CheckupRow::score).average().orElse(0);
            runs.save(new CheckupRunEntity(Instant.now(), operator, rows.size(), avg,
                    json.writeValueAsString(rows)));
        } catch (Exception e) {
            log.warn("巡检结果落库失败", e);
        }
    }

    CheckupRow checkOne(Instance ins, String operator) {
        int score = ins.baseHealth();
        List<String> deductions = new ArrayList<>();
        for (String cmd : TEMPLATE) {
            try {
                var r = diag.execute(ins.instanceId(), cmd, "系统", operator);
                if (!r.success()) {
                    deductions.add("执行失败");
                    break;
                }
                parseDeductions(r.output(), deductions);
            } catch (Exception e) {
                deductions.add("执行失败");
                break;
            }
        }
        for (String d : deductions) {
            score -= switch (d) {
                case "full_gc" -> 8;
                case "old_gen_high" -> 10;
                case "thread_growth" -> 6;
                case "cpu_high" -> 12;
                case "执行失败" -> 5;
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
