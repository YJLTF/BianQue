package com.bianque.server.config;

import com.bianque.server.exec.LocalAsShExecutor;
import com.bianque.server.exec.MockDiagExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 执行通道装配：
 * - 拟真通道恒在（模拟靶场实例）；
 * - 本机 as.sh 真实通道按 bianque.execution.local-enabled 开关装配（生产建议关闭）。
 */
@Configuration
public class ExecutorConfig {

    @Bean
    public MockDiagExecutor mockDiagExecutor() {
        return new MockDiagExecutor();
    }

    @Bean
    @ConditionalOnProperty(name = "bianque.execution.local-enabled", havingValue = "true")
    public LocalAsShExecutor localAsShExecutor(
            @Value("${bianque.execution.boot-jar-path:docs/research/arthas-inspect/arthas-boot.jar}") String bootJarPath,
            @Value("${bianque.execution.java-path:${java.home}/bin/java}") String javaPath) {
        return new LocalAsShExecutor(bootJarPath, javaPath);
    }
}
