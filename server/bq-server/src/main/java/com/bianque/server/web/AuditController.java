package com.bianque.server.web;

import com.bianque.server.repo.AuditRepository;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计中心 API：谁·何时·何实例·何命令·何结果。列表走数据库侧分页。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditRepository audit;

    public AuditController(AuditRepository audit) {
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> query(@RequestParam(required = false) String source,
                                     @RequestParam(required = false) String operator,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        AuditRepository.PageResult r = audit.searchPage(source, operator, page, size);
        return Map.of("total", r.total(), "records", r.records());
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
