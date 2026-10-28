package com.bianque.server.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    /** 与 server/pom.xml 版本保持一致（发布时同步更新） */
    @Value("${bianque.app.version:1.0.0}")
    private String version = "1.0.0";

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "app", "bianque-server",
                "version", version,
                "status", "UP",
                "time", Instant.now().toString());
    }
}
