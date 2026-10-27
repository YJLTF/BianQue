package com.bianque.server.config;

import com.bianque.server.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：/api/** 全量鉴权，放行登录与探活；/docs/** 静态文档保持公开只读。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // 告警接入走独立 X-Alert-Token 认证（外部系统无平台登录态）
                // 平台 Agent 节点走独立 X-Agent-Token 认证（bq-agent 无平台登录态）
                .excludePathPatterns("/api/auth/login", "/api/health", "/api/alerts/ingest", "/api/agent/**");
    }
}
