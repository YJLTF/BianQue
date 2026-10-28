package com.bianque.server.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 生产模式安全门禁（fail-fast，防带病上线）：
 * 演示关闭（bianque.demo-enabled=false）时节点接入令牌必须配置——否则 /api/agent/** 在生产网络
 * 上完全开放：任意来源可注册伪造节点、上报假进程清单、领取并回填诊断命令单。
 * 演示模式维持开放接入（内网演示语义，README 红线）。
 */
@Component
public class SecurityGuard {

    private final boolean demoEnabled;
    private final String agentToken;

    public SecurityGuard(@Value("${bianque.demo-enabled:true}") boolean demoEnabled,
                         @Value("${bianque.agent.token:}") String agentToken) {
        this.demoEnabled = demoEnabled;
        this.agentToken = agentToken == null ? "" : agentToken.trim();
    }

    @PostConstruct
    void validate() {
        String v = violation(demoEnabled, agentToken);
        if (v != null) {
            throw new IllegalStateException(v);
        }
    }

    /** 返回违规原因，null=通过（静态便于单测） */
    static String violation(boolean demoEnabled, String agentToken) {
        if (!demoEnabled && (agentToken == null || agentToken.isBlank())) {
            return "生产模式安全门禁：节点接入令牌未配置（bianque.agent.token / AGENT_TOKEN）。"
                    + "生产模式下 /api/agent/** 禁止开放接入，请在 .env 配置 AGENT_TOKEN 后重启。";
        }
        return null;
    }
}
