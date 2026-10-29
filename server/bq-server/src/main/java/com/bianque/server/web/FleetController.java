package com.bianque.server.web;

import com.bianque.server.exec.LocalProcessScanner;
import com.bianque.server.model.Types.AppSummary;
import com.bianque.server.model.Types.Channels;
import com.bianque.server.model.Types.Instance;
import com.bianque.server.model.Types.Overview;
import com.bianque.server.repo.AuditRepository;
import com.bianque.server.repo.FleetRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final LocalProcessScanner scanner;
    private final boolean localEnabled;

    public FleetController(FleetRepository fleet, AuditRepository audit, LocalProcessScanner scanner,
                           @Value("${bianque.execution.local-enabled:false}") boolean localEnabled) {
        this.fleet = fleet;
        this.audit = audit;
        this.scanner = scanner;
        this.localEnabled = localEnabled;
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
        // 通道分布与 DiagService.route 一致：探针(agent-*)/本机纳管=真实，其余（靶场种子）=拟真
        long real = all.stream()
                .filter(i -> i.instanceId().startsWith("agent-") || i.localAgent()).count();
        return new Overview(apps.size(), all.size(),
                all.stream().filter(Instance::online).count(),
                apps.stream().mapToInt(AppSummary::healthScore).average().orElse(0),
                audit.count(), new Channels(real, all.size() - real), apps);
    }

    @GetMapping("/instances")
    public List<Instance> instances(@RequestParam(required = false) String appId) {
        return appId == null || appId.isBlank() ? fleet.allInstances() : fleet.byApp(appId);
    }

    // ---- 本机真实进程纳管（bianque.execution.local-enabled 时开放） ----

    /** 本机 Java 进程清单（jps -l），作为 attach 目标发现 */
    @GetMapping("/local-processes")
    public Map<String, Object> localProcesses() {
        requireLocalEnabled();
        return Map.of(
                "localEnabled", true,
                "processes", scanner.scan());
    }

    /** 纳管：注册本机进程为平台实例（后续诊断自动走 as.sh 真实通道） */
    @PostMapping("/local-processes/{pid}/attach")
    public Instance attach(@PathVariable long pid, HttpServletRequest request) {
        requireLocalEnabled();
        String mainClass = scanner.scan().stream()
                .filter(p -> p.pid() == pid)
                .map(LocalProcessScanner.LocalProcess::mainClass)
                .findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "进程不存在或已退出: " + pid));
        Instance ins = fleet.registerLocal(pid, mainClass);
        audit.append("人工", DiagController.operator(request), ins.instanceId(),
                "attach 本机进程（纳管 " + mainClass + "）", 0, "ok");
        return ins;
    }

    private void requireLocalEnabled() {
        if (!localEnabled) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "本机纳管未启用（bianque.execution.local-enabled=false）");
        }
    }
}
