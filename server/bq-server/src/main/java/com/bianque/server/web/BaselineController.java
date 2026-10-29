package com.bianque.server.web;

import com.bianque.server.service.BlacklistService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全基线只读接口：前端"管理设置-安全基线"与文档中心风险徽标共用同一份字典。
 * 黑名单三份口径：内置基线（不可削减）/ 页面追加项 / 生效合集（基线 ∪ 追加）。
 */
@RestController
@RequestMapping("/api/baseline")
public class BaselineController {

    private final BlacklistService blacklist;

    public BaselineController(BlacklistService blacklist) {
        this.blacklist = blacklist;
    }

    @GetMapping
    public Map<String, Object> baseline() {
        return Map.of(
                "bindLocalOnly", true,
                "authRequired", true,
                "sessionTimeoutMinutes", 30,
                "blockedCommands", blacklist.baseline(),
                "blockedExtraCommands", blacklist.extra(),
                "blockedEffectiveCommands", blacklist.effective(),
                "auditEnabled", true);
    }
}
