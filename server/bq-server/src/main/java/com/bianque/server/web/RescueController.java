package com.bianque.server.web;

import com.bianque.server.alert.AlertService;
import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;
import com.bianque.server.service.RescueService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扁鹊·急诊 API：告警列表、一键取证、外部告警接入（入站预留）。
 */
@RestController
@RequestMapping("/api/alerts")
public class RescueController {

    private final RescueService rescue;
    private final AlertService alerts;

    public RescueController(RescueService rescue, AlertService alerts) {
        this.rescue = rescue;
        this.alerts = alerts;
    }

    public record IngestRequest(String alertId, String appId, String instanceId,
                                String message, String severity, String source) {
    }

    @GetMapping
    public List<Alert> list() {
        return rescue.listAlerts();
    }

    /** 外部告警接入（入站预留）：X-Alert-Token 认证，不走平台登录态；配置见 bianque.alerts.ingest-token */
    @PostMapping("/ingest")
    public Alert ingest(@RequestBody IngestRequest req,
                        @RequestHeader(value = "X-Alert-Token", required = false) String token) {
        return alerts.ingest(token, req.alertId(), req.appId(), req.instanceId(),
                req.message(), req.severity(), req.source());
    }

    @PostMapping("/{alertId}/rescue")
    public RescueReport rescue(@PathVariable String alertId, HttpServletRequest request) {
        return rescue.rescue(alertId, DiagController.operator(request));
    }

    @GetMapping("/{alertId}/report")
    public Map<String, Object> report(@PathVariable String alertId) {
        RescueReport r = rescue.report(alertId);
        return r == null ? Map.of("found", false) : Map.of("found", true, "report", r);
    }
}
