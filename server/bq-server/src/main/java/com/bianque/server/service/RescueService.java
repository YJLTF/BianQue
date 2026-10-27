package com.bianque.server.service;

import com.bianque.server.alert.AlertService;
import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;
import com.bianque.server.model.Types.RescueStep;
import com.bianque.server.store.RescueReportEntity;
import com.bianque.server.store.RescueReportJpa;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 扁鹊·急诊：告警联动现场取证。告警触发 → 按模板顺序采集（thread→memory→jvm）→ 聚合现场报告并落库。
 * 告警来源：演示种子（内存）+ 外部告警系统接入（/api/alerts/ingest，落 MySQL）。
 */
@Service
public class RescueService {

    private static final Logger log = LoggerFactory.getLogger(RescueService.class);
    private static final Pattern OLD_GEN = Pattern.compile("old\\.gen\\.usage=\\s*(\\d+)%");
    private static final Pattern THREAD_TOTAL = Pattern.compile("线程总数=\\s*(\\d+)");

    private final DiagService diag;
    private final RescueReportJpa reports;
    private final AlertService alerts;
    private final ObjectMapper json;

    public RescueService(DiagService diag, RescueReportJpa reports, AlertService alerts, ObjectMapper json) {
        this.diag = diag;
        this.reports = reports;
        this.alerts = alerts;
        this.json = json;
    }

    public List<Alert> listAlerts() {
        return alerts.listAlerts();
    }

    public RescueReport rescue(String alertId) {
        return rescue(alertId, DiagService.SYSTEM);
    }

    public RescueReport rescue(String alertId, String operator) {
        Alert alert = alerts.find(alertId);
        if (alert == null) {
            throw new IllegalArgumentException("告警不存在: " + alertId);
        }
        long begin = System.currentTimeMillis();
        List<RescueStep> steps = new ArrayList<>();
        String threadOut = run(steps, 1, "最忙线程 + 死锁检测", "thread -n 5", alert.instanceId(), operator);
        String memoryOut = run(steps, 2, "内存水位", "memory", alert.instanceId(), operator);
        String jvmOut = run(steps, 3, "JVM / GC 统计", "jvm", alert.instanceId(), operator);

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
        persist(operator, report);
        alerts.markRescued(alertId, report);
        return report;
    }

    public RescueReport report(String alertId) {
        Optional<RescueReportEntity> hit = reports.findFirstByAlertIdOrderByIdDesc(alertId);
        if (hit.isEmpty()) {
            return null;
        }
        try {
            return json.readValue(hit.get().payloadJson, RescueReport.class);
        } catch (Exception e) {
            log.warn("取证报告反序列化失败: {}", alertId, e);
            return null;
        }
    }

    private void persist(String operator, RescueReport report) {
        try {
            reports.save(new RescueReportEntity(report.alertId(), report.instanceId(),
                    report.generatedAt(), operator, json.writeValueAsString(report)));
        } catch (Exception e) {
            log.warn("取证报告落库失败: {}", report.alertId(), e);
        }
    }

    private String run(List<RescueStep> steps, int order, String name, String command,
                       String instanceId, String operator) {
        String out = diag.execute(instanceId, command, "急诊", operator).output();
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
