package com.bianque.server.config;

import com.bianque.server.exec.FastPathClient;
import com.bianque.server.exec.LocalArthasSessionManager;
import com.bianque.server.exec.LocalAsShExecutor;
import com.bianque.server.exec.MockDiagExecutor;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 执行通道装配：
 * - 拟真通道恒在（模拟靶场实例）；
 * - 本机真实通道按 bianque.execution.local-enabled 开关装配（生产建议关闭），
 *   内含常驻会话快路径（HTTP 直连 agent）与一次性 boot 兜底。
 */
@Configuration
public class ExecutorConfig {

    @Bean
    public MockDiagExecutor mockDiagExecutor() {
        return new MockDiagExecutor();
    }

    @Bean
    public FastPathClient fastPathClient(
            @Value("${bianque.execution.exec-timeout-seconds:30}") long execTimeoutSeconds) {
        return new FastPathClient(Duration.ofSeconds(execTimeoutSeconds));
    }

    @Bean
    @ConditionalOnProperty(name = "bianque.execution.local-enabled", havingValue = "true")
    public LocalArthasSessionManager sessionManager(
            FastPathClient client,
            @Value("${bianque.execution.boot-jar-path:docs/research/arthas-inspect/arthas-boot.jar}") String bootJarPath,
            @Value("${bianque.execution.java-path:${java.home}/bin/java}") String javaPath,
            @Value("${bianque.execution.fast-port-base:18600}") int portBase,
            @Value("${bianque.execution.fast-path:true}") boolean fastEnabled) {
        return new LocalArthasSessionManager(client, bootJarPath, javaPath, portBase, fastEnabled);
    }

    @Bean
    @ConditionalOnProperty(name = "bianque.execution.local-enabled", havingValue = "true")
    public LocalAsShExecutor localAsShExecutor(
            @Value("${bianque.execution.boot-jar-path:docs/research/arthas-inspect/arthas-boot.jar}") String bootJarPath,
            @Value("${bianque.execution.java-path:${java.home}/bin/java}") String javaPath,
            ObjectProvider<LocalArthasSessionManager> sessionManager) {
        return new LocalAsShExecutor(bootJarPath, javaPath, sessionManager.getIfAvailable());
    }
}
