package com.bianque.server.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    /** 版本取构建产物 build-info（spring-boot-maven-plugin build-info 生成，随 pom 版本走）；
        IDE 直跑无 build-info 时回落配置项（默认 dev），不硬编码版本号（曾因发布漏改恒报 1.1.0） */
    private final String version;

    /** 演示模式标志（登录页据此显隐演示账号快捷填充） */
    private final boolean demoEnabled;

    public HealthController(ObjectProvider<BuildProperties> buildInfo,
                            @Value("${bianque.app.version:dev}") String configuredVersion,
                            @Value("${bianque.demo-enabled:true}") boolean demoEnabled) {
        BuildProperties info = buildInfo.getIfAvailable();
        this.version = info != null ? info.getVersion() : configuredVersion;
        this.demoEnabled = demoEnabled;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "app", "bianque-server",
                "version", version,
                "status", "UP",
                "demo", demoEnabled,
                "time", Instant.now().toString());
    }
}
