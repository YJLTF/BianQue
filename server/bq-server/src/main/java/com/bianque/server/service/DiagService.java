package com.bianque.server.service;

import com.bianque.core.diag.CommandAllowlist;
import com.bianque.core.diag.DiagCommand;
import com.bianque.core.diag.DiagExecutor;
import com.bianque.core.diag.DiagResult;
import com.bianque.core.diag.RiskLevel;
import com.bianque.server.exec.LocalAsShExecutor;
import com.bianque.server.exec.MockDiagExecutor;
import com.bianque.server.model.Types.Instance;
import com.bianque.server.model.Types.SessionInfo;
import com.bianque.server.repo.AuditTrail;
import com.bianque.server.repo.FleetRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 诊断执行门面：平台是唯一执行入口。
 * 白名单拦截 → 会话托管（默认 30 分钟自动回收）→ 通道分发（本机实例走 as.sh 真实通道，
 * 模拟靶场走拟真通道）→ 审计留痕（含真实操作人）。
 */
@Service
public class DiagService {

    /** 系统触发（无登录态的内部链路）使用的操作人标识 */
    public static final String SYSTEM = "system";

    private final CommandAllowlist allowlist = new CommandAllowlist();
    private final MockDiagExecutor mockExecutor;
    private final LocalAsShExecutor localExecutor; // local-enabled=false 时为 null
    private final AuditTrail audit;
    private final FleetRepository fleet;
    private final Map<String, SessionHandle> sessions = new ConcurrentHashMap<>();
    private final Duration sessionTtl;

    private record SessionHandle(SessionInfo info) {
    }

    public DiagService(MockDiagExecutor mockExecutor,
                       ObjectProvider<LocalAsShExecutor> localExecutor,
                       AuditTrail audit,
                       FleetRepository fleet,
                       @Value("${bianque.session.ttl-minutes:30}") long ttlMinutes) {
        this.mockExecutor = mockExecutor;
        this.localExecutor = localExecutor.getIfAvailable();
        this.audit = audit;
        this.fleet = fleet;
        this.sessionTtl = Duration.ofMinutes(ttlMinutes);
    }

    /** 通道分发：localAgent 实例走真实 as.sh，其余走拟真通道 */
    private DiagExecutor route(Instance ins) {
        if (ins != null && ins.localAgent()) {
            if (localExecutor == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "本机真实通道未启用（bianque.execution.local-enabled=false）");
            }
            return localExecutor;
        }
        return mockExecutor;
    }

    /** as.sh --select 目标：本机实例用主类名，靶场实例用 instanceId */
    private String selectTarget(Instance ins) {
        return ins != null && ins.mainClass() != null && !ins.mainClass().isBlank()
                ? ins.mainClass() : ins.instanceId();
    }

    public SessionInfo openSession(String instanceId, String operator) {
        Instant now = Instant.now();
        SessionInfo info = new SessionInfo(UUID.randomUUID().toString(), instanceId,
                operator, now, now.plus(sessionTtl), true);
        sessions.put(info.sessionId(), new SessionHandle(info));
        audit.append("人工", operator, instanceId, "attach（开启诊断会话 " + shortId(info.sessionId()) + "）", 800, "ok");
        return info;
    }

    /** 执行诊断命令：高危一律拒绝（无审批流，安全红线优先） */
    public DiagResult execute(String instanceId, String commandText, String source) {
        return execute(instanceId, commandText, source, SYSTEM);
    }

    public DiagResult execute(String instanceId, String commandText, String source, String operator) {
        if (!allowlist.isAllowed(commandText)) {
            audit.append(source, operator, instanceId, commandText, 0, "blocked");
            return DiagResult.fail("已拦截：命令命中高危黑名单 " + allowlist.blockedCommands(), 0);
        }
        Instance ins = fleet.find(instanceId).orElse(null);
        if (ins == null && instanceId.startsWith("local-")) {
            // 本机纳管实例登记在内存：后端重启后需重新 attach
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "纳管实例不存在（后端重启后需重新 attach）: " + instanceId);
        }
        String sessionId = ensureSession(instanceId, operator);
        RiskLevel risk = CommandAllowlist.ENHANCED.contains(
                commandText.trim().split("\\s+")[0].toLowerCase()) ? RiskLevel.MID : RiskLevel.LOW;
        String selectTarget = selectTarget(ins);
        DiagCommand command = new DiagCommand(selectTarget, instanceId, risk.name(), risk, commandText, 60);
        DiagResult result = route(ins).execute(command);
        audit.append(source, operator, instanceId, commandText, result.costMs(),
                result.success() ? "ok" : "fail");
        return result;
    }

    /** 结束会话：reset + stop（防增强残留） */
    public SessionInfo closeSession(String sessionId) {
        SessionHandle h = sessions.remove(sessionId);
        if (h == null) {
            return null;
        }
        audit.append("人工", h.info().openedBy(), h.info().instanceId(),
                "reset + stop（会话回收 " + shortId(sessionId) + "）", 300, "ok");
        return new SessionInfo(sessionId, h.info().instanceId(), h.info().openedBy(),
                h.info().openedAt(), h.info().expiresAt(), false);
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

    private String ensureSession(String instanceId, String operator) {
        SessionInfo existing = activeSessionOf(instanceId);
        return existing != null ? existing.sessionId() : openSession(instanceId, operator).sessionId();
    }

    private String shortId(String id) {
        return id.substring(0, 8);
    }
}
