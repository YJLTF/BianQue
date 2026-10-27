package com.bianque.server.web;

import com.bianque.server.model.Types.AppSummary;
import com.bianque.server.model.Types.Instance;
import com.bianque.server.model.Types.Overview;
import com.bianque.server.repo.AuditRepository;
import com.bianque.server.repo.FleetRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 台账与总览 API。
 */
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private final FleetRepository fleet;
    private final AuditRepository audit;
    private final String executionMode;

    public FleetController(FleetRepository fleet, AuditRepository audit,
                           @Value("${bianque.execution.mode:mock}") String executionMode) {
        this.fleet = fleet;
        this.audit = audit;
        this.executionMode = executionMode;
    }

    @GetMapping("/overview")
    public Overview overview() {
        List<Instance> all = fleet.allInstances();
        List<AppSummary> apps = fleet.appIds().stream().map(appId -> {
            List<Instance> list = fleet.byApp(appId);
            int health = (int) Math.round(list.stream().filter(i -> i.online())
                    .mapToInt(Instance::baseHealth).average().orElse(90));
            String status = health >= 90 ? "健康" : (health >= 80 ? "需关注" : "异常");
            return new AppSummary(appId, appId, list.size(),
                    list.stream().filter(Instance::online).count(), health, status,
                    appId.equals("order-app") ? "CPU 96% · 已自动取证" : "—");
        }).toList();
        return new Overview(apps.size(), all.size(),
                all.stream().filter(Instance::online).count(),
                apps.stream().mapToInt(AppSummary::healthScore).average().orElse(0),
                audit.count(), executionMode, apps);
    }

    @GetMapping("/instances")
    public List<Instance> instances(@RequestParam(required = false) String appId) {
        return appId == null || appId.isBlank() ? fleet.allInstances() : fleet.byApp(appId);
    }
}
