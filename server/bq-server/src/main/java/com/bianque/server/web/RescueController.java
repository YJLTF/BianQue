package com.bianque.server.web;

import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;
import com.bianque.server.service.RescueService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扁鹊·急诊 API：告警列表与一键取证。
 */
@RestController
@RequestMapping("/api/alerts")
public class RescueController {

    private final RescueService rescue;

    public RescueController(RescueService rescue) {
        this.rescue = rescue;
    }

    @GetMapping
    public List<Alert> list() {
        return rescue.listAlerts();
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
