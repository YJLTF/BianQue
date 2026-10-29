package com.bianque.server.web;

import com.bianque.server.auth.AuthContext;
import com.bianque.server.auth.AuthInterceptor;
import com.bianque.server.service.SysConfigService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参数中心 API（/api/admin/config，AuthInterceptor 对 /api/admin/** 强制 admin:user）：
 * 清单 / 修改 / 恢复默认；每次变更入审计哈希链。
 */
@RestController
@RequestMapping("/api/admin/config")
public class SysConfigController {

    private final SysConfigService config;

    public SysConfigController(SysConfigService config) {
        this.config = config;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return config.list();
    }

    public record UpdateReq(String value) {
    }

    @PutMapping("/{key}")
    public Map<String, Object> update(@PathVariable String key, @RequestBody UpdateReq req,
                                      HttpServletRequest request) {
        return config.update(key, req.value(), operator(request));
    }

    @PostMapping("/{key}/reset")
    public Map<String, Object> reset(@PathVariable String key, HttpServletRequest request) {
        return config.reset(key, operator(request));
    }

    private static String operator(HttpServletRequest request) {
        Object auth = request.getAttribute(AuthInterceptor.ATTR);
        return auth instanceof AuthContext a ? a.username() + "(" + a.displayName() + ")" : "system";
    }
}
