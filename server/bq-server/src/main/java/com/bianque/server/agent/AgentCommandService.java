package com.bianque.server.agent;

import com.bianque.server.store.AgentCommandEntity;
import com.bianque.server.store.AgentCommandJpa;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台 Agent 命令单服务：入队 → 节点心跳原子领单 → 回传结果。
 * 诊断线程用 await 轮询等结果（命令单状态是唯一真相源，节点离线也能给出明确错误）。
 */
@Service
public class AgentCommandService {

    private static final Logger log = LoggerFactory.getLogger(AgentCommandService.class);
    /** 节点领单后超过此时长仍未回传，视为节点中断 */
    private static final Duration STALE_RUNNING = Duration.ofMinutes(3);

    private final AgentCommandJpa jpa;

    public AgentCommandService(AgentCommandJpa jpa) {
        this.jpa = jpa;
    }

    public long enqueue(String agentId, String instanceId, String command) {
        return jpa.save(new AgentCommandEntity(agentId, instanceId, command)).id;
    }

    /** 心跳领单：同一节点同时只派一单（上一单 RUNNING 时不再派发，避免节点端乱序执行） */
    @Transactional
    public AgentCommandEntity claimNext(String agentId) {
        if (jpa.existsByAgentIdAndStatus(agentId, AgentCommandEntity.RUNNING)) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            Optional<AgentCommandEntity> next =
                    jpa.findFirstByAgentIdAndStatusOrderByIdAsc(agentId, AgentCommandEntity.PENDING);
            if (next.isEmpty()) {
                return null;
            }
            Long id = next.get().id;
            if (jpa.claim(id) == 1) {
                return jpa.findById(id).orElse(null);
            }
            // 刚被并发心跳抢走，重试下一单
        }
        return null;
    }

    public void submitResult(long id, String status, String output, String outputKind, String error, long costMs) {
        jpa.findById(id).ifPresent(e -> {
            e.status = status;
            e.output = output;
            e.outputKind = outputKind;
            e.error = error;
            e.costMs = costMs;
            e.finishedAt = Instant.now();
            jpa.save(e);
        });
    }

    /** 轮询等结果；超时返回 null（命令单保持 RUNNING，由 reaper 兜底判死） */
    public AgentCommandEntity await(long id, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<AgentCommandEntity> e = jpa.findById(id);
            if (e.isPresent() && !AgentCommandEntity.PENDING.equals(e.get().status)
                    && !AgentCommandEntity.RUNNING.equals(e.get().status)) {
                return e.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** 兜底判死：RUNNING 超时未回传的命令单置 FAILED（节点掉线时诊断方能快速失败） */
    @Scheduled(fixedDelay = 30_000)
    public void reapStale() {
        List<AgentCommandEntity> stale = jpa.findByStatusAndCreatedAtBefore(
                AgentCommandEntity.RUNNING, Instant.now().minus(STALE_RUNNING));
        for (AgentCommandEntity e : stale) {
            e.status = AgentCommandEntity.FAILED;
            e.error = "节点心跳中断，命令未回传（超时判死）";
            e.finishedAt = Instant.now();
            jpa.save(e);
            log.warn("agent 命令单 {} 超时判死（节点 {}）", e.id, e.agentId);
        }
    }
}
