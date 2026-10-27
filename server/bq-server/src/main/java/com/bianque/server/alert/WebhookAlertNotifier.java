package com.bianque.server.alert;

import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 Webhook 告警通知（出站预留的默认实现）：新告警/取证完成时 POST JSON 到 bianque.alerts.webhook-url。
 * 不绑定企微/钉钉等具体 IM——自有告警系统提供该 HTTP 接口即可接入；报文结构见 payload()。
 */
public class WebhookAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertNotifier.class);

    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public WebhookAlertNotifier(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        log.info("告警出站通知：Webhook → {}", webhookUrl);
    }

    @Override
    public void onAlert(Alert alert) {
        post("alert.created", alert, null);
    }

    @Override
    public void onRescueCompleted(Alert alert, RescueReport report) {
        post("alert.rescued", alert, report.conclusion());
    }

    private void post(String event, Alert alert, String conclusion) {
        try {
            String body = JSON.writeValueAsString(payload(event, alert, conclusion));
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("告警 webhook 非 2xx: {} {}", resp.statusCode(), event);
            }
        } catch (Exception e) {
            log.warn("告警 webhook 发送失败: {} ({})", event, e.getMessage());
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private Map<String, Object> payload(String event, Alert alert, String conclusion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", event);
        m.put("alertId", alert.alertId());
        m.put("appId", alert.appId());
        m.put("instanceId", alert.instanceId());
        m.put("severity", alert.severity());
        m.put("message", alert.message());
        m.put("status", alert.status());
        m.put("time", alert.time().toString());
        if (conclusion != null) {
            m.put("conclusion", conclusion);
        }
        return m;
    }
}
