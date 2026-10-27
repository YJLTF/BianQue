package com.bianque.server.web;

import com.bianque.core.diag.CommandAllowlist;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全基线只读接口：前端"管理设置-安全基线"与文档中心风险徽标共用同一份字典。
 */
@RestController
@RequestMapping("/api/baseline")
public class BaselineController {

    private final CommandAllowlist allowlist = new CommandAllowlist();

    @GetMapping
    public Map<String, Object> baseline() {
        return Map.of(
                "bindLocalOnly", true,
                "authRequired", true,
                "sessionTimeoutMinutes", 30,
                "blockedCommands", allowlist.blockedCommands(),
                "auditEnabled", true);
    }
}
