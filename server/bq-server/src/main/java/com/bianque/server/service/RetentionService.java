package com.bianque.server.service;

import com.bianque.server.store.AgentCommandEntity;
import com.bianque.server.store.AgentCommandJpa;
import com.bianque.server.store.DiagArtifactJpa;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据保留清理（参数中心可配保留天数）：诊断工件（火焰图等）默认 7 天、
 * 节点命令单默认 14 天；每 6 小时跑一次。审计哈希链为合规留痕不做自动清理。
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final DiagArtifactJpa artifacts;
    private final AgentCommandJpa commands;
    private final SysConfigService config;

    public RetentionService(DiagArtifactJpa artifacts, AgentCommandJpa commands, SysConfigService config) {
        this.artifacts = artifacts;
        this.commands = commands;
        this.config = config;
    }

    @Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 120_000L)
    @Transactional
    public void cleanup() {
        try {
            long artifactDays = Math.max(1, config.intValue("data.artifact-retention-days"));
            long commandDays = Math.max(1, config.intValue("data.command-retention-days"));
            long now = System.currentTimeMillis();
            long aCut = Instant.ofEpochMilli(now).minusSeconds(artifactDays * 86400).toEpochMilli();
            long cCut = Instant.ofEpochMilli(now).minusSeconds(commandDays * 86400).toEpochMilli();
            long aCount = artifacts.deleteByCreatedAtBefore(Instant.ofEpochMilli(aCut));
            long cCount = commands.deleteByStatusInAndCreatedAtBefore(
                    List.of(AgentCommandEntity.DONE, AgentCommandEntity.FAILED), Instant.ofEpochMilli(cCut));
            if (aCount > 0 || cCount > 0) {
                log.info("数据保留清理：工件 {} 条（>{}天）、命令单 {} 条（>{}天）", aCount, artifactDays, cCount, commandDays);
            }
        } catch (Exception e) {
            log.warn("数据保留清理失败: {}", e.toString());
        }
    }
}
