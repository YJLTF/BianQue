package com.bianque.server.exec;

import com.bianque.core.diag.DiagCommand;
import com.bianque.core.diag.DiagExecutor;
import com.bianque.core.diag.DiagResult;

/**
 * 演示通道：按命令族返回结构化拟真输出，输出内嵌可解析的指标行（供巡检打分/急诊结论消费）。
 * 使 V0.1.0 在无真实 agent 的环境下即可端到端使用；mode=local 时切换为 as.sh 真实执行。
 */
public class MockDiagExecutor implements DiagExecutor {

    @Override
    public DiagResult execute(DiagCommand command) {
        long begin = System.currentTimeMillis();
        String head = command.commandText().trim().split("\\s+")[0].toLowerCase();
        String output = render(head, command);
        sleep(150 + Math.abs(command.instanceId().hashCode()) % 300);
        return DiagResult.ok(output, System.currentTimeMillis() - begin);
    }

    private String render(String cmd, DiagCommand c) {
        int seed = Math.abs((c.instanceId() + cmd).hashCode());
        return switch (cmd) {
            case "thread" -> """
                    线程总数= %d
                    "OrderWorker-7" Id=142 CPU=%d.%d%% WAITING on LinkedBlockingQueue@5f3b2a
                        at java.util.concurrent.LinkedBlockingQueue.take(...)
                        at com.bianque.order.OrderWorker.run(OrderWorker.java:88)
                    "Inventory-pool-fetch" Id=207 CPU=0.8%% TIMED_WAITING
                        at com.bianque.inventory.InventoryClient.borrowObject(InventoryClient.java:132)
                    "RMI Scheduler(0)" Id=31 CPU=0.1%% TIMED_WAITING
                    死锁检测=无""".formatted(300 + seed % 200, 80 + seed % 18, seed % 10);
            case "memory" -> """
                    heap.usage= %d%%
                    old.gen.usage= %d%%
                    metaspace.usage= %d%%
                    eden: 1024MB used / 2048MB
                    old  : %dMB used / 4096MB
                    meta : %dMB used / 512MB""".formatted(
                    55 + seed % 30, 60 + seed % 30, 50 + seed % 20,
                    2457 + seed % 1000, 256 + seed % 100);
            case "jvm" -> """
                    jvm.info= OpenJDK 64-Bit Server VM (17.0.9+9)
                    gc.young.count= %d
                    gc.full.count= %d
                    thread.count= %d
                    uptime.hours= %d""".formatted(800 + seed % 400, seed % 5, 300 + seed % 200, 100 + seed % 600);
            case "trace" -> """
                    trace com.bianque.order.OrderService placeOrder '#cost>100'
                    `---[4213ms] OrderService:placeOrder
                        +---[2880ms 68%%] InventoryClient:borrow
                        +---[640ms 15%%] OrderDao:insert
                        `---[560ms 13%%] PayClient:precreate""";
            case "watch" -> """
                    method=com.bianque.order.OrderService.placeOrder location=AtExit
                    `---ts=2026-10-27 15:30:01; [cost=214ms]
                        params: [OrderRequest(orderNo=SO-20261027-0001, amount=299.00)]
                        returnObj: OrderResult(success=true)""";
            case "sc" -> (seed % 5 == 0)
                    ? """
                    class.info= not found
                    sc.matched= 0"""
                    : """
                    com.bianque.demo.TargetClass
                    class.loader= app
                    source.jar= /opt/app/lib/demo-%d.%d.0.jar
                    vulnerable= %s
                    sc.matched= 1""".formatted(seed % 3, seed % 5, seed % 7 == 1 ? "true" : "false");
            case "dashboard" -> """
                    Memory: heap 62%% old %d%% meta %d%%
                    Thread: total %d WAITING %d
                    CPU: %d%%
                    GC: young %d/s full %d""".formatted(
                    60 + seed % 30, 50 + seed % 20, 300 + seed % 200, 120 + seed % 80, 20 + seed % 70,
                    seed % 5, seed % 2);
            case "profiler" -> c.commandText().contains("stop")
                    ? "profiler output file: /tmp/arthas-output/demo-flame-%d.html%n[html] demo flame graph"
                            .formatted(seed % 10000)
                    : "profiler started (event=cpu)\n[demo] sampling...";
            case "cat" -> (c.commandText().contains(".html")
                    ? "<html><head><meta charset=\"utf-8\"></head><body><h1>demo flame graph</h1>"
                            + "<canvas id=\"flame\" width=\"800\" height=\"200\"></canvas></body></html>"
                    : "ok\ncommand= " + c.commandText() + "\ninstance= " + c.instanceId());
            default -> """
                    ok
                    command= %s
                    instance= %s""".formatted(c.commandText(), c.instanceId());
        };
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
