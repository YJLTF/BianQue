package com.bianque.server.web;

import com.bianque.server.model.Types.AuditEntry;
import com.bianque.server.repo.AuditRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计中心 API：谁·何时·何实例·何命令·何结果。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditRepository audit;

    public AuditController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditEntry> query(@RequestParam(required = false) String source,
                                  @RequestParam(required = false) String operator,
                                  @RequestParam(defaultValue = "100") int limit) {
        return audit.query(source, operator, limit);
    }

    @GetMapping("/stats")
    public java.util.Map<String, Object> stats() {
        return audit.stats();
    }

    /** 哈希链完整性校验：任一历史记录被篡改即断链 */
    @GetMapping("/verify")
    public java.util.Map<String, Object> verify() {
        return audit.verifyChain();
    }
}
