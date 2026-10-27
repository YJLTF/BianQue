package com.bianque.server.repo;

import com.bianque.server.model.Types.Instance;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 应用/实例台账（V0.1.0 种子数据；S1 接 tunnel 心跳与 MySQL）。
 */
@Repository
public class FleetRepository {

    private final Map<String, Instance> instances = new ConcurrentHashMap<>();

    public FleetRepository() {
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
    }

    private void seed(String appId, String instanceId, String host, boolean online, int baseHealth) {
        instances.put(instanceId, new Instance(instanceId, appId, host, "4.3.5", online,
                "127.0.0.1".equals(host), baseHealth, null));
    }

    /** 纳管本机真实 JVM 进程：instanceId=local-<pid>，执行走 as.sh 真实通道 */
    public Instance registerLocal(long pid, String mainClass) {
        String instanceId = "local-" + pid;
        String shortName = mainClass.contains("/")
                ? mainClass.substring(mainClass.lastIndexOf('/') + 1)
                : mainClass;
        Instance ins = new Instance(instanceId, shortName, "127.0.0.1", "4.3.5", true,
                true, 95, mainClass);
        instances.put(instanceId, ins);
        return ins;
    }

    public void remove(String instanceId) {
        instances.remove(instanceId);
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
