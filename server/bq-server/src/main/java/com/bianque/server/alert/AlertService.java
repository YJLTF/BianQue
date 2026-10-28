package com.bianque.server.alert;

import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;
import com.bianque.server.store.AlertEventEntity;
import com.bianque.server.store.AlertEventJpa;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 告警中心（入站预留）：外部告警系统经 ingest API 推入（X-Alert-Token 认证），
 * 事件落 MySQL 并触发 AlertNotifier 出站通知；取证状态回写。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertEventJpa events;
    private final AlertNotifier notifier;
    private final String ingestToken;
    /** 演示靶场的种子告警（内存，仅演示模式）；真实告警一律走 ingest 落库 */
    private final List<Alert> seeds;

    public AlertService(AlertEventJpa events, AlertNotifier notifier,
                        @Value("${bianque.alerts.ingest-token:}") String ingestToken,
                        @Value("${bianque.demo-enabled:true}") boolean demoEnabled) {
        this.events = events;
        this.notifier = notifier;
        this.ingestToken = ingestToken == null ? "" : ingestToken.trim();
        this.seeds = demoEnabled ? List.of(
                new Alert("AL-1001", Instant.now().minusSeconds(600), "order-app", "pod-3",
                        "CPU 使用率 96%，持续 3 分钟", "严重", "待取证"),
                new Alert("AL-1002", Instant.now().minusSeconds(1500), "user-app", "inst-2",
                        "Full GC 频繁（4 次/小时）", "警告", "待取证"),
                new Alert("AL-1003", Instant.now().minusSeconds(3700), "gateway", "edge-1",
                        "5xx 比例 3.2%", "警告", "待取证")) : List.of();
    }

    public boolean ingestEnabled() {
        return !ingestToken.isEmpty();
    }

    /** 外部告警推入（入站接口核心） */
    public Alert ingest(String token, String alertId, String appId, String instanceId,
                        String message, String severity, String source) {
        requireToken(token);
        if (appId == null || appId.isBlank() || instanceId == null || instanceId.isBlank()
                || message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appId/instanceId/message 必填");
        }
        String id = alertId == null || alertId.isBlank()
                ? "AL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() : alertId;
        AlertEventEntity e = new AlertEventEntity(id, appId, instanceId, message,
                normalizeSeverity(severity),
                source == null || source.isBlank() ? "external" : source, "待取证", Instant.now());
        events.save(e);
        Alert alert = toAlert(e);
        log.info("告警接入: {} {} {}/{} — {}", id, e.severity, appId, instanceId, message);
        notifier.onAlert(alert);
        return alert;
    }

    /** 外部系统级别值各异，归一化为平台三级：严重 / 警告 / 提示 */
    static String normalizeSeverity(String s) {
        if (s == null || s.isBlank()) {
            return "警告";
        }
        return switch (s.trim().toLowerCase()) {
            case "critical", "fatal", "error", "p0" -> "严重";
            case "warning", "warn", "p1" -> "警告";
            case "info", "informational", "notice", "p2" -> "提示";
            default -> s.trim();
        };
    }

    /** 全量告警 = 种子（演示靶场）+ 落库事件（真实接入） */
    public List<Alert> listAlerts() {
        List<Alert> all = new ArrayList<>();
        List<AlertEventEntity> db = events.findAll();
        db.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        for (AlertEventEntity e : db) {
            all.add(toAlert(e));
        }
        Map<String, Boolean> seen = new ConcurrentHashMap<>();
        all.forEach(a -> seen.put(a.alertId(), true));
        for (Alert s : seeds) {
            if (!seen.containsKey(s.alertId())) {
                all.add(s);
            }
        }
        return all;
    }

    /** 取证前定位告警（种子或落库事件） */
    public Alert find(String alertId) {
        return seeds.stream().filter(a -> a.alertId().equals(alertId)).findFirst()
                .orElseGet(() -> events.findFirstByAlertIdOrderByIdDesc(alertId)
                        .map(AlertService::toAlert).orElse(null));
    }

    /** 取证完成回调：更新状态并出站通知 */
    public void markRescued(String alertId, RescueReport report) {
        events.findFirstByAlertIdOrderByIdDesc(alertId).ifPresent(e -> {
            e.status = "已取证";
            events.save(e);
            notifier.onRescueCompleted(toAlert(e), report);
        });
    }

    private void requireToken(String token) {
        if (ingestToken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "告警接入未启用（bianque.alerts.ingest-token 未配置）");
        }
        if (!ingestToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "X-Alert-Token 无效");
        }
    }

    private static Alert toAlert(AlertEventEntity e) {
        return new Alert(e.alertId, e.createdAt, e.appId, e.instanceId, e.message, e.severity, e.status);
    }
}
