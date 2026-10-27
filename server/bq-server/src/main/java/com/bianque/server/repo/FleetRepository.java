package com.bianque.server.repo;

import com.bianque.server.model.Types.Instance;
import com.bianque.server.store.ManagedInstanceEntity;
import com.bianque.server.store.ManagedInstanceJpa;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 应用/实例台账：模拟靶场实例由代码种子；本机纳管实例落库（重启免重新 attach）。
 */
@Repository
public class FleetRepository {

    private final Map<String, Instance> instances = new ConcurrentHashMap<>();
    private final ManagedInstanceJpa managed;

    public FleetRepository(ManagedInstanceJpa managed) {
        this.managed = managed;
    }

    @PostConstruct
    public void init() {
        seed("order-app", "pod-1", "10.1.2.1", true, 92);
        seed("order-app", "pod-2", "10.1.2.2", true, 88);
        seed("order-app", "pod-3", "10.1.2.3", true, 71);
        seed("order-app", "pod-4", "10.1.2.4", false, 90);
        seed("user-app", "inst-1", "10.2.0.1", true, 90);
        seed("user-app", "inst-2", "10.2.0.2", true, 83);
        seed("inventory-app", "inv-1", "10.3.0.1", true, 96);
        seed("inventory-app", "inv-2", "10.3.0.2", true, 95);
        seed("gateway", "edge-1", "10.4.0.1", true, 98);
        seed("gateway", "edge-2", "10.4.0.2", true, 97);
        seed("member-app", "mem-1", "10.5.0.1", true, 95);
        seed("report-job", "runner-1", "10.6.0.1", false, 86);
        // 恢复已纳管的本机实例（进程是否存活由执行期校验给出明确错误）
        if (managed != null) {
            managed.findAll().forEach(e -> instances.put(e.instanceId,
                    new Instance(e.instanceId, e.appId, e.host, e.agentVersion, e.online,
                            true, e.baseHealth, e.mainClass)));
        }
    }

    private void seed(String appId, String instanceId, String host, boolean online, int baseHealth) {
        instances.put(instanceId, new Instance(instanceId, appId, host, "4.3.5", online,
                "127.0.0.1".equals(host), baseHealth, null));
    }

    /** 纳管本机真实 JVM 进程：instanceId=local-<pid>，执行走真实通道；登记落库 */
    public Instance registerLocal(long pid, String mainClass) {
        String instanceId = "local-" + pid;
        String shortName = mainClass.contains("/")
                ? mainClass.substring(mainClass.lastIndexOf('/') + 1)
                : mainClass;
        Instance ins = new Instance(instanceId, shortName, "127.0.0.1", "4.3.5", true,
                true, 95, mainClass);
        instances.put(instanceId, ins);
        try {
            if (managed.findByInstanceId(instanceId).isEmpty()) {
                managed.save(new ManagedInstanceEntity(instanceId, shortName, "127.0.0.1", "4.3.5",
                        true, 95, mainClass, Instant.now()));
            }
        } catch (Exception ignore) {
            // 落库失败不影响本次会话内使用（如无 DB 的 H2 内存演示）
        }
        return ins;
    }

    /** 平台 Agent 上报的实例：仅内存登记（节点每 5s 心跳重报，掉线自动摘除，无需落库） */
    public void put(Instance ins) {
        instances.put(ins.instanceId(), ins);
    }

    public void remove(String instanceId) {
        instances.remove(instanceId);
        try {
            managed.deleteByInstanceId(instanceId);
        } catch (Exception ignore) {
            // 同上
        }
    }

    public List<Instance> allInstances() {
        return List.copyOf(instances.values());
    }

    public List<Instance> byApp(String appId) {
        return instances.values().stream().filter(i -> i.appId().equals(appId)).toList();
    }

    public List<String> appIds() {
        return instances.values().stream().map(Instance::appId).distinct().sorted().toList();
    }

    public Optional<Instance> find(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }
}
