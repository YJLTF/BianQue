package com.bianque.server.service;

import com.bianque.core.diag.DiagResult;
import com.bianque.core.diag.ResultParsers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 双实例基线对比诊断（火山图）：目标实例 vs 基线实例，同一命令集重复采样 N 轮
 * （memory / dashboard / jvm），逐指标做倍数变化（log2）与显著性（Welch t）。
 * 样本不足（<2 个有效窗口）的指标降级为"仅倍数变化"，产品口径为参考性对比。
 */
@Service
public class CompareService {

    /** 每轮采样命令集（保持少量、只读、可解析） */
    private static final List<String> ROUND_COMMANDS = List.of("memory", "dashboard -n 1", "jvm");

    private final DiagService diag;

    public CompareService(DiagService diag) {
        this.diag = diag;
    }

    public record CompareReq(String targetInstanceId, String baseInstanceId, Integer rounds, Integer intervalMs) {
    }

    public record MetricStat(String key, double targetMean, double baseMean,
                             List<Double> targetSamples, List<Double> baseSamples,
                             double foldChangeLog2, double pValue, boolean significant, boolean sufficient) {
    }

    /** 执行采样与对比（同步，耗时 ≈ rounds × 命令数 × 2 实例） */
    public Map<String, Object> compare(CompareReq req, String operator) {
        int rounds = req.rounds() == null ? 5 : Math.max(2, Math.min(req.rounds(), 10));
        long intervalMs = req.intervalMs() == null ? 500 : Math.max(200, Math.min(req.intervalMs(), 5_000));
        String target = req.targetInstanceId();
        String base = req.baseInstanceId();
        if (target == null || base == null || target.isBlank() || base.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "须同时选择目标实例与基线实例");
        }
        if (target.equals(base)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标与基线不能是同一实例");
        }
        Map<String, List<Double>> tSamples = new TreeMap<>();
        Map<String, List<Double>> bSamples = new TreeMap<>();
        for (int round = 1; round <= rounds; round++) {
            for (String cmd : ROUND_COMMANDS) {
                collect(diag.execute(target, cmd, "对比诊断", operator), cmd, tSamples);
                collect(diag.execute(base, cmd, "对比诊断", operator), cmd, bSamples);
            }
            if (round < rounds) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        List<MetricStat> metrics = new ArrayList<>();
        List<String> insufficient = new ArrayList<>();
        var keys = new TreeMap<String, int[]>();
        tSamples.forEach((k, v) -> keys.computeIfAbsent(k, x -> new int[2])[0] = v.size());
        bSamples.forEach((k, v) -> keys.computeIfAbsent(k, x -> new int[2])[1] = v.size());
        for (var e : keys.entrySet()) {
            String key = e.getKey();
            List<Double> t = tSamples.getOrDefault(key, List.of());
            List<Double> b = bSamples.getOrDefault(key, List.of());
            double tm = mean(t);
            double bm = mean(b);
            double fold = (tm <= 0 || bm <= 0) ? 0 : log2(tm / bm);
            boolean sufficient = t.size() >= 2 && b.size() >= 2;
            double p = sufficient ? ResultParsers.welchPValue(toArray(t), toArray(b)) : 1.0;
            if (!sufficient) {
                insufficient.add(key);
            }
            metrics.add(new MetricStat(key, round4(tm), round4(bm), t, b,
                    round4(fold), round6(p), sufficient && p < 0.05, sufficient));
        }
        metrics.sort((a, b) -> Double.compare(Math.abs(b.foldChangeLog2()), Math.abs(a.foldChangeLog2())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rounds", rounds);
        out.put("commands", ROUND_COMMANDS);
        out.put("target", target);
        out.put("base", base);
        out.put("metrics", metrics);
        out.put("insufficient", insufficient);
        return out;
    }

    private void collect(DiagResult r, String cmd, Map<String, List<Double>> into) {
        if (r == null || !r.success() || r.output() == null) {
            return;
        }
        ResultParsers.scalarMetrics(cmd, r.output())
                .forEach((k, v) -> into.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
    }

    private static double mean(List<Double> v) {
        return v.isEmpty() ? 0 : v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double[] toArray(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }
}
