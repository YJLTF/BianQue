package com.bianque.server.service;

import com.bianque.core.diag.CommandAllowlist;
import com.bianque.core.diag.DiagCommand;
import com.bianque.core.diag.DiagExecutor;
import com.bianque.core.diag.DiagResult;
import com.bianque.core.diag.RiskLevel;
import com.bianque.server.model.Types.SessionInfo;
import com.bianque.server.repo.AuditRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 诊断执行门面：平台是唯一执行入口。
 * 白名单拦截 → 会话托管（默认 30 分钟自动回收）→ 通道分发 → 审计留痕。
 */
@Service
public class DiagService {

    /** 操作人：V0.1.0 未接 SSO，统一演示账号；S1 接 OIDC 后取登录态 */
    public static final String OPERATOR = "张三(演示)";

    private final CommandAllowlist allowlist = new CommandAllowlist();
    private final DiagExecutor executor;
    private final AuditRepository audit;
    private final Map<String, SessionHandle> sessions = new ConcurrentHashMap<>();
    private final Duration sessionTtl;

    private record SessionHandle(SessionInfo info) {
    }

    public DiagService(DiagExecutor executor,
                       AuditRepository audit,
                       @Value("${bianque.session.ttl-minutes:30}") long ttlMinutes) {
        this.executor = executor;
        this.audit = audit;
        this.sessionTtl = Duration.ofMinutes(ttlMinutes);
    }

    public SessionInfo openSession(String instanceId) {
        Instant now = Instant.now();
        SessionInfo info = new SessionInfo(UUID.randomUUID().toString(), instanceId,
                OPERATOR, now, now.plus(sessionTtl), true);
        sessions.put(info.sessionId(), new SessionHandle(info));
        audit.append("人工", OPERATOR, instanceId, "attach（开启诊断会话 " + shortId(info.sessionId()) + "）", 800, "ok");
        return info;
    }

    /** 执行诊断命令：高危一律拒绝（V0.1.0 无审批流，安全红线优先） */
    public DiagResult execute(String instanceId, String commandText, String source) {
        if (!allowlist.isAllowed(commandText)) {
            audit.append(source, OPERATOR, instanceId, commandText, 0, "blocked");
            return DiagResult.fail("已拦截：命令命中高危黑名单 " + allowlist.blockedCommands(), 0);
        }
        String sessionId = ensureSession(instanceId);
        RiskLevel risk = CommandAllowlist.ENHANCED.contains(
                commandText.trim().split("\\s+")[0].toLowerCase()) ? RiskLevel.MID : RiskLevel.LOW;
        DiagCommand command = new DiagCommand(instanceId, instanceId, risk.name(), risk, commandText, 60);
        DiagResult result = executor.execute(command);
        audit.append(source, OPERATOR, instanceId, commandText, result.costMs(),
                result.success() ? "ok" : "fail");
        SessionHandle h = sessions.get(sessionId);
        if (h != null) {
            sessions.put(sessionId, new SessionHandle(new SessionInfo(sessionId, instanceId, OPERATOR,
                    h.info().openedAt(), h.info().expiresAt().plusMillis(0), h.info().active())));
        }
        return result;
    }

    /** 结束会话：reset + stop（防增强残留） */
    public SessionInfo closeSession(String sessionId) {
        SessionHandle h = sessions.remove(sessionId);
        if (h == null) {
            return null;
        }
        audit.append("人工", OPERATOR, h.info().instanceId(), "reset + stop（会话回收 " + shortId(sessionId) + "）", 300, "ok");
        return new SessionInfo(sessionId, h.info().instanceId(), h.info().openedAt().toString().isEmpty()
                ? OPERATOR : OPERATOR, h.info().openedAt(), h.info().expiresAt(), false);
    }

    public SessionInfo activeSessionOf(String instanceId) {
        return sessions.values().stream()
                .map(SessionHandle::info)
                .filter(s -> s.active() && s.instanceId().equals(instanceId) && s.expiresAt().isAfter(Instant.now()))
                .findFirst()
                .orElse(null);
    }

    /** 兜底回收：过期会话自动 reset+stop（审计可见） */
    @Scheduled(fixedDelay = 60_000)
    public void reclaimExpired() {
        Instant now = Instant.now();
        sessions.values().stream()
                .filter(h -> h.info().expiresAt().isBefore(now))
                .map(h -> h.info().sessionId())
                .toList()
                .forEach(this::closeSession);
    }

    private String ensureSession(String instanceId) {
        SessionInfo existing = activeSessionOf(instanceId);
        return existing != null ? existing.sessionId() : openSession(instanceId).sessionId();
    }

    private String shortId(String id) {
        return id.substring(0, 8);
    }
}
