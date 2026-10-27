package com.bianque.server.service;

import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;
import com.bianque.server.model.Types.RescueStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 扁鹊·急诊：告警联动现场取证。告警触发 → 按模板顺序采集（thread→memory→jvm→profiler）→ 聚合现场报告。
 */
@Service
public class RescueService {

    private static final Pattern OLD_GEN = Pattern.compile("old\\.gen\\.usage=\\s*(\\d+)%");
    private static final Pattern THREAD_TOTAL = Pattern.compile("线程总数=\\s*(\\d+)");

    private final DiagService diag;
    private final List<Alert> alerts = new CopyOnWriteArrayList<>(List.of(
            new Alert("AL-1001", Instant.now().minusSeconds(600), "order-app", "pod-3",
                    "CPU 使用率 96%，持续 3 分钟", "严重", "待取证"),
            new Alert("AL-1002", Instant.now().minusSeconds(1500), "user-app", "inst-2",
                    "Full GC 频繁（4 次/小时）", "警告", "待取证"),
            new Alert("AL-1003", Instant.now().minusSeconds(3700), "gateway", "edge-1",
                    "5xx 比例 3.2%", "警告", "待取证")));
    private final Map<String, RescueReport> reports = new ConcurrentHashMap<>();

    public RescueService(DiagService diag) {
        this.diag = diag;
    }

    public List<Alert> listAlerts() {
        return List.copyOf(alerts);
    }

    public RescueReport rescue(String alertId) {
        Alert alert = alerts.stream().filter(a -> a.alertId().equals(alertId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("告警不存在: " + alertId));
        long begin = System.currentTimeMillis();
        List<RescueStep> steps = new ArrayList<>();
        String threadOut = run(steps, 1, "最忙线程 + 死锁检测", "thread -n 5", alert.instanceId());
        String memoryOut = run(steps, 2, "内存水位", "memory", alert.instanceId());
        String jvmOut = run(steps, 3, "JVM / GC 统计", "jvm", alert.instanceId());

        int threadTotal = findInt(THREAD_TOTAL, threadOut, 0);
        int oldGen = findInt(OLD_GEN, memoryOut, 0);
        String conclusion = buildConclusion(threadTotal, oldGen, alert);
        RescueReport report = new RescueReport(alertId, alert.instanceId(), List.copyOf(steps),
                conclusion,
                List.of("OrderWorker-7 → LinkedBlockingQueue.take (CPU 96.4%)",
                        "Inventory-pool-fetch → borrowObject:132 (0.8%)",
                        "RMI Scheduler(0) (0.1%)"),
                Map.of("oldGen", oldGen, "threads", threadTotal),
                System.currentTimeMillis() - begin, Instant.now());
        reports.put(alertId, report);
        alerts.remove(alert);
        alerts.add(new Alert(alert.alertId(), alert.time(), alert.appId(), alert.instanceId(),
                alert.message(), alert.severity(), "已取证"));
        return report;
    }

    public RescueReport report(String alertId) {
        return reports.get(alertId);
    }

    private String run(List<RescueStep> steps, int order, String name, String command, String instanceId) {
        String out = diag.execute(instanceId, command, "急诊").output();
        steps.add(new RescueStep(order, name, command, "done", snippet(out)));
        return out;
    }

    private String buildConclusion(int threads, int oldGen, Alert alert) {
        StringBuilder sb = new StringBuilder("疑似结论：");
        if (threads >= 450) {
            sb.append(threads).append(" 个业务线程 WAITING，阻塞在 inventory 连接池获取；");
        }
        if (oldGen >= 85) {
            sb.append("老年代水位 ").append(oldGen).append("%，注意泄漏斜率；");
        }
        sb.append("触发源：").append(alert.message()).append("。建议：扩容连接池并观察，必要时开启 tt 留痕。");
        return sb.toString();
    }

    private static int findInt(Pattern p, String text, int def) {
        Matcher m = p.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    private static String snippet(String out) {
        return out.length() <= 160 ? out : out.substring(0, 160) + "…";
    }
}
