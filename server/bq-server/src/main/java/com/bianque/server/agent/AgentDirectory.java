package com.bianque.server.agent;

import com.bianque.server.model.Types.Instance;
import com.bianque.server.repo.FleetRepository;
import com.bianque.server.service.SysConfigService;
import com.bianque.server.store.AgentNodeEntity;
import com.bianque.server.store.AgentNodeJpa;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 平台 Agent 节点目录：节点注册/心跳时刷新台账，并把上报的 JVM 进程同步为机队实例
 * （instanceId=agent-<agentId>-<pid>，执行走 Agent 通道）。进程列表内存态即可——
 * 节点按参数中心配置的节奏上报，超窗未收到心跳即判定离线并从机队摘除。
 */
@Service
public class AgentDirectory {

    public record NodeView(String agentId, String host, String hostname, String os, String javaVersion,
                           String agentVersion, Instant firstSeen, Instant lastHeartbeat, boolean online,
                           List<ProcessView> processes) {
    }

    public record ProcessView(long pid, String mainClass, String instanceId) {
    }

    private final AgentNodeJpa nodes;
    private final FleetRepository fleet;
    private final SysConfigService config;
    private final Map<String, Map<Long, String>> processes = new ConcurrentHashMap<>(); // agentId -> pid -> mainClass

    public AgentDirectory(AgentNodeJpa nodes, FleetRepository fleet, SysConfigService config) {
        this.nodes = nodes;
        this.fleet = fleet;
        this.config = config;
    }

    /** 注册（幂等）：已存在的 agentId 刷新台账，新节点生成 id = <hostname>-<rand4> */
    public synchronized AgentNodeEntity register(String agentId, String host, String hostname, String os,
                                                 String javaVersion, String agentVersion) {
        AgentNodeEntity e = agentId != null && !agentId.isBlank()
                ? nodes.findById(agentId).orElse(null) : null;
        if (e == null) {
            String id = agentId != null && !agentId.isBlank() ? agentId : newAgentId(hostname);
            e = new AgentNodeEntity(id, host, hostname, os, javaVersion, agentVersion);
        } else {
            e.host = host;
            e.hostname = hostname;
            e.os = os;
            e.javaVersion = javaVersion;
            e.agentVersion = agentVersion;
        }
        e.lastHeartbeat = Instant.now();
        return nodes.save(e);
    }

    /** 心跳：刷新在线时间并同步进程 → 机队实例 */
    public void heartbeat(String agentId, String host, List<long[]> pids, List<String> mainClasses) {
        nodes.findById(agentId).ifPresent(e -> {
            if (host != null && !host.isBlank()) {
                e.host = host;
            }
            e.lastHeartbeat = Instant.now();
            nodes.save(e);
        });
        syncProcesses(agentId, pids, mainClasses);
    }

    private void syncProcesses(String agentId, List<long[]> pids, List<String> mainClasses) {
        AgentNodeEntity node = nodes.findById(agentId).orElse(null);
        String host = node != null ? node.host : "unknown";
        Map<Long, String> current = processes.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>());
        Set<Long> reported = new HashSet<>();
        for (int i = 0; i < pids.size() && i < mainClasses.size(); i++) {
            long pid = pids.get(i)[0];
            String mainClass = mainClasses.get(i);
            reported.add(pid);
            current.put(pid, mainClass);
            String instanceId = instanceId(agentId, pid);
            fleet.put(new Instance(instanceId, shortName(mainClass), host, "4.3.5", true, true, 95, mainClass));
        }
        // 进程消失的实例从机队摘除
        for (Long pid : new ArrayList<>(current.keySet())) {
            if (!reported.contains(pid)) {
                current.remove(pid);
                fleet.remove(instanceId(agentId, pid));
            }
        }
    }

    public boolean online(String agentId) {
        return nodes.findById(agentId)
                .map(e -> e.lastHeartbeat != null
                        && e.lastHeartbeat.isAfter(Instant.now()
                                .minus(Duration.ofSeconds(config.intValue("agent.online-window-seconds")))))
                .orElse(false);
    }

    /** 删除节点（调用方须保证已离线）：台账、内存进程表与机队实例一并清理；返回摘除的实例数 */
    public synchronized int removeNode(String agentId) {
        if (!nodes.existsById(agentId)) {
            return -1;
        }
        nodes.deleteById(agentId);
        int removed = 0;
        Map<Long, String> ps = processes.remove(agentId);
        if (ps != null) {
            for (Long pid : ps.keySet()) {
                fleet.remove(instanceId(agentId, pid));
                removed++;
            }
        }
        return removed;
    }

    public boolean exists(String agentId) {
        return agentId != null && nodes.existsById(agentId);
    }

    public List<NodeView> listView() {
        List<NodeView> out = new ArrayList<>();
        for (AgentNodeEntity e : nodes.findAll()) {
            List<ProcessView> ps = processes.getOrDefault(e.agentId, Map.of()).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(en -> new ProcessView(en.getKey(), en.getValue(), instanceId(e.agentId, en.getKey())))
                    .toList();
            out.add(new NodeView(e.agentId, e.host, e.hostname, e.os, e.javaVersion, e.agentVersion,
                    e.firstSeen, e.lastHeartbeat, online(e.agentId), ps));
        }
        out.sort((a, b) -> a.agentId.compareTo(b.agentId));
        return out;
    }

    public List<ProcessView> processesOf(String agentId) {
        return processes.getOrDefault(agentId, Map.of()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(en -> new ProcessView(en.getKey(), en.getValue(), instanceId(agentId, en.getKey())))
                .toList();
    }

    public static String instanceId(String agentId, long pid) {
        return "agent-" + agentId + "-" + pid;
    }

    /** instanceId = agent-<agentId>-<pid> → agentId（agentId 自身不含连字符约束由注册侧保证） */
    public static String agentIdOf(String instanceId) {
        String rest = instanceId.substring("agent-".length());
        return rest.substring(0, rest.lastIndexOf('-'));
    }

    public static long pidOf(String instanceId) {
        return Long.parseLong(instanceId.substring(instanceId.lastIndexOf('-') + 1));
    }

    private static String newAgentId(String hostname) {
        String h = hostname == null || hostname.isBlank() ? "node" : hostname;
        // agentId 不含连字符，保证 instanceId 可反解
        h = h.replaceAll("[^A-Za-z0-9]", "");
        return h + "-" + Integer.toHexString((int) (Math.random() * 0xffff));
    }

    private static String shortName(String mainClass) {
        String s = mainClass.replace('\\', '/');
        String last = s.substring(s.lastIndexOf('/') + 1);
        return last.endsWith(".jar") ? last.substring(0, last.length() - 4) : last;
    }
}
