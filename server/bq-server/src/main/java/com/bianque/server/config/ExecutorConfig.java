package com.bianque.server.config;

import com.bianque.core.diag.DiagExecutor;
import com.bianque.server.exec.LocalAsShExecutor;
import com.bianque.server.exec.MockDiagExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 执行通道装配：bianque.execution.mode = mock（默认，拟真演示）| local（本机 as.sh 真实执行）。
 */
@Configuration
public class ExecutorConfig {

    @Bean
    public DiagExecutor diagExecutor(
            @Value("${bianque.execution.mode:mock}") String mode,
            @Value("${bianque.execution.as-sh-path:../docs/research/arthas-inspect/as.sh}") String asShPath) {
        return "local".equalsIgnoreCase(mode)
                ? new LocalAsShExecutor(asShPath)
                : new MockDiagExecutor();
    }
}
