package com.bianque.server.alert;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 告警通知装配（出站预留）：配置 bianque.alerts.webhook-url 时走通用 Webhook（自有告警系统）；
 * 否则兜底日志通知。接入自有系统也可直接实现 AlertNotifier 注册为 Bean，优先于两者。
 */
@Configuration
public class AlertNotifierConfig {

    @Bean
    @ConditionalOnProperty("bianque.alerts.webhook-url")
    public AlertNotifier webhookAlertNotifier(
            @Value("${bianque.alerts.webhook-url}") String webhookUrl) {
        return new WebhookAlertNotifier(webhookUrl);
    }

    @Bean
    @ConditionalOnMissingBean(AlertNotifier.class)
    public AlertNotifier logAlertNotifier() {
        return new LogAlertNotifier();
    }
}
