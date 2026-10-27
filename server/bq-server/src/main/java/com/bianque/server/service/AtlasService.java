package com.bianque.server.service;

import com.bianque.server.model.Types.AtlasHit;
import com.bianque.server.model.Types.AtlasReport;
import com.bianque.server.model.Types.Instance;
import com.bianque.server.repo.AuditTrail;
import com.bianque.server.repo.FleetRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 扁鹊·档案：运行时资产盘点与漏洞应急（批量 sc -d 扫描，串行分批）。
 */
@Service
public class AtlasService {

    private final DiagService diag;
    private final FleetRepository fleet;
    private final AuditTrail audit;

    public AtlasService(DiagService diag, FleetRepository fleet, AuditTrail audit) {
        this.diag = diag;
        this.fleet = fleet;
        this.audit = audit;
    }

    public AtlasReport scan(String targetClass) {
        return scan(targetClass, DiagService.SYSTEM);
    }

    public AtlasReport scan(String targetClass, String operator) {
        long begin = System.currentTimeMillis();
        List<AtlasHit> hits = new ArrayList<>();
        List<Instance> online = fleet.allInstances().stream().filter(Instance::online).toList();
        for (Instance ins : online) {
            String out = diag.execute(ins.instanceId(), "sc -d " + targetClass, "人工", operator).output();
            boolean found = out.contains("sc.matched= 1");
            String verdict;
            if (!found) {
                verdict = "未命中";
            } else if (out.contains("vulnerable")) {
                verdict = "漏洞路径可达";
            } else if (ins.baseHealth() < 80) {
                verdict = "存在旧版本 jar";
            } else {
                verdict = "已修复版本";
            }
            hits.add(new AtlasHit(ins.instanceId(), targetClass, found,
                    found ? "/opt/app/lib/demo-1.2.0.jar" : "-",
                    found ? "AppClassLoader" : "-",
                    verdict));
        }
        long vulnerable = hits.stream().filter(h -> "漏洞路径可达".equals(h.verdict())).count();
        long foundCount = hits.stream().filter(AtlasHit::classFound).count();
        audit.append("人工", operator, "全部应用(" + online.size() + ")",
                "批量 sc -d " + targetClass, System.currentTimeMillis() - begin, "ok");
        return new AtlasReport(targetClass, online.size(), (int) foundCount, (int) vulnerable,
                System.currentTimeMillis() - begin, hits, Instant.now());
    }
}
