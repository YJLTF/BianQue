package com.bianque.server.agent;

import com.bianque.core.diag.DiagCommand;
import com.bianque.core.diag.DiagExecutor;
import com.bianque.core.diag.DiagResult;
import com.bianque.server.exec.FastPathClient;
import com.bianque.server.store.AgentCommandEntity;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 平台 Agent 通道：命令单入队 → 节点心跳领单执行 → 结果回传。
 * 节点快路径回传 arthas agent 原始 JSON（由平台统一渲染），一次性兜底回传控制台文本。
 */
@Service
public class AgentChannelExecutor implements DiagExecutor {

    private final AgentCommandService commands;

    public AgentChannelExecutor(AgentCommandService commands) {
        this.commands = commands;
    }

    @Override
    public DiagResult execute(DiagCommand command) {
        long begin = System.currentTimeMillis();
        String agentId = AgentDirectory.agentIdOf(command.instanceId());
        long id = commands.enqueue(agentId, command.instanceId(), command.commandText());
        AgentCommandEntity done = commands.await(id,
                Duration.ofSeconds(command.timeoutSeconds() + 30L));
        long cost = System.currentTimeMillis() - begin;
        if (done == null) {
            return DiagResult.fail("执行超时：节点未在时限内回传结果（命令单 " + id + "）", cost);
        }
        if (!AgentCommandEntity.DONE.equals(done.status)) {
            return DiagResult.fail(done.error == null ? "节点执行失败" : done.error, cost);
        }
        String text = "agent-json".equals(done.outputKind)
                ? FastPathClient.renderAgentJson(done.output) : done.output;
        if (text == null || text.isBlank()) {
            return DiagResult.fail("节点回传内容为空或渲染失败", cost);
        }
        return DiagResult.ok(text, done.costMs > 0 ? done.costMs : cost);
    }

    /** 会话回收：通知节点 stop 目标 arthas agent（防增强残留） */
    public void notifyClose(String instanceId) {
        String agentId = AgentDirectory.agentIdOf(instanceId);
        commands.enqueue(agentId, instanceId, "__close__");
    }
}
