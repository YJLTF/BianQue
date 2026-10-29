package com.bianque.core.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.core.diag.ResultParsers.JvmView;
import com.bianque.core.diag.ResultParsers.MemoryView;
import com.bianque.core.diag.ResultParsers.MonitorPoint;
import com.bianque.core.diag.ResultParsers.ThreadView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 结构化解析：以 Arthas 4.3.5 官方文档样例 + 拟真通道格式为准入用例 */
class ResultParsersTest {

    private static final String ARTHAS_THREAD_TABLE = """
            $ thread
            Threads Total: 33, NEW: 0, RUNNABLE: 9, BLOCKED: 0, WAITING: 3, TIMED_WAITING: 4, TERMINATED: 0, Internal threads: 17
            ID   NAME                           GROUP          PRIORITY  STATE     %CPU      DELTA_TIME TIME      INTERRUPT DAEMON
            -1   C2 CompilerThread0             -              -1        -         5.06      0.010      0:0.973   false     true
            -1   C1 CompilerThread0             -              -1        -         0.95      0.001      0:0.603   false     true
            23   arthas-command-execute         system         5         RUNNABLE  0.17      0.000      0:0.226   false     true
            """;

    private static final String ARTHAS_THREAD_TOP = """
            $ thread -n 3
            "C1 CompilerThread0" [Internal] cpuUsage=1.63% deltaTime=3ms time=1170ms


            "arthas-command-execute" Id=23 cpuUsage=0.11% deltaTime=0ms time=401ms RUNNABLE
                at java.management@11.0.7/sun.management.ThreadImpl.dumpThreads0(Native Method)
                at com.taobao.arthas.core.command.monitor200.ThreadCommand.process(ThreadCommand.java:122)
            """;

    private static final String ARTHAS_MEMORY = """
            $ memory
            Memory                           used      total      max        usage
            heap                             32M       256M       4096M      0.79%
            g1_eden_space                    11M       68M        -1         16.18%
            g1_old_gen                       17M       184M       4096M      0.43%
            g1_survivor_space                4M        4M         -1         100.00%
            nonheap                          35M       39M        -1         89.55%
            metaspace                        26M       27M        -1         96.88%
            mapped                           0K        0K         -          0.00%
            """;

    private static final String MOCK_MEMORY = """
            Memory: heap 72%%
            heap.usage= 72%%
            old.gen.usage= 87%%
            metaspace.usage= 63%%
            eden: 1024MB used / 2048MB
            old  : 3584MB used / 4096MB""".replace("%%", "%");

    private static final String MOCK_JVM = """
            jvm.info= OpenJDK 64-Bit Server VM (17.0.9+9)
            gc.young.count= 942
            gc.full.count= 3
            thread.count= 388
            uptime.hours= 314""";

    private static final String MOCK_DASHBOARD = """
            Memory: heap 62% old 72% meta 52%
            Thread: total 402 WAITING 182
            CPU: 22%
            GC: young 2/s full 0""";

    private static final String ARTHAS_MONITOR = """
            $ monitor -c 5 demo.MathGame primeFactors
            Press Ctrl+C to abort.
            Affect(class-cnt:1 , method-cnt:1) cost in 94 ms.
             timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
            -----------------------------------------------------------------------------------------------
             2018-12-03 19:06:38  demo.MathGame  primeFactors  5      1        4     1.15        80.00%

             timestamp            class          method        total  success  fail  avg-rt(ms)  fail-rate
            -----------------------------------------------------------------------------------------------
             2018-12-03 19:06:43  demo.MathGame  primeFactors  5      3        2     42.29       40.00%
            """;

    @Test
    void 官方thread表格_状态分布与CPU排序() {
        ThreadView v = ResultParsers.parseThread(ARTHAS_THREAD_TABLE);
        assertNotNull(v);
        assertEquals(33, v.total());
        // 汇总行为权威分布（明细行只是子集不重复计数）
        assertEquals(9L, v.stateCounts().get("RUNNABLE"));
        assertEquals(4L, v.stateCounts().get("TIMED_WAITING"));
        assertEquals("C2 CompilerThread0", v.topCpu().get(0).name()); // %CPU 倒序
        assertEquals(23, v.topCpu().stream().filter(t -> t.id() == 23).findFirst().orElseThrow().id());
    }

    @Test
    void 官方threadTop_块形态解析() {
        ThreadView v = ResultParsers.parseThread(ARTHAS_THREAD_TOP);
        assertNotNull(v);
        assertEquals(2, v.topCpu().size());
        assertEquals(1.63, v.topCpu().get(0).cpuPct(), 1e-9); // 已按 CPU 倒序
        assertEquals("RUNNABLE", v.topCpu().stream().filter(t -> t.id() == 23).findFirst().orElseThrow().state());
        assertEquals(-1, v.topCpu().get(0).id()); // 无 Id= 的内部线程
    }

    @Test
    void 拟真thread_行格式() {
        String out = """
                线程数= 388
                "OrderWorker-7" Id=142 CPU=80.8% WAITING on LinkedBlockingQueue@5f3b2a
                    at java.util.concurrent.LinkedBlockingQueue.take(...)
                "Inventory-pool-fetch" Id=207 CPU=0.8% TIMED_WAITING""";
        ThreadView v = ResultParsers.parseThread(out);
        assertNotNull(v);
        assertEquals(388, v.total());
        assertEquals(2, v.topCpu().size());
        assertEquals("WAITING", v.topCpu().get(0).state());
    }

    @Test
    void 官方memory表格_池水位与关键池提取() {
        MemoryView v = ResultParsers.parseMemory(ARTHAS_MEMORY);
        assertNotNull(v);
        assertEquals(0.79, v.heapPct(), 1e-9);
        assertEquals(0.43, v.oldPct(), 1e-9);
        assertEquals(96.88, v.metaPct(), 1e-9);
        assertEquals(7, v.pools().size());
        assertEquals(32 * 1024 * 1024, v.pools().get(0).usedBytes(), 1e-6);
        assertEquals(-1, v.pools().get(1).maxBytes(), 1e-6);
    }

    @Test
    void 拟真memory_键值与尺寸行() {
        MemoryView v = ResultParsers.parseMemory(MOCK_MEMORY);
        assertNotNull(v);
        assertEquals(72, v.heapPct(), 1e-9);
        assertEquals(87, v.oldPct(), 1e-9);
        assertEquals(63, v.metaPct(), 1e-9);
        assertTrue(v.pools().stream().anyMatch(p -> "eden".equals(p.name()) && p.usedBytes() == 1024L * 1024 * 1024));
        assertTrue(v.pools().stream().anyMatch(p -> "old".equals(p.name()) && p.usagePct() > 80));
    }

    @Test
    void dashboard单行_线程内存CPU一次解析() {
        Map<String, Object> m = ResultParsers.parse("dashboard -n 1", MOCK_DASHBOARD);
        assertNotNull(m);
        assertEquals("dashboard", m.get("kind"));
        ThreadView t = (ThreadView) m.get("thread");
        assertEquals(402, t.total());
        MemoryView mv = (MemoryView) m.get("memory");
        assertEquals(22.0, mv.cpuPct(), 1e-9);
    }

    @Test
    void jvm键值_GC计数求和() {
        JvmView v = ResultParsers.parseJvm(MOCK_JVM);
        assertNotNull(v);
        assertEquals(945, v.gcTotalCount());
        assertEquals("388", v.kv().get("thread.count"));
        assertEquals("jvm", ResultParsers.parse("jvm", MOCK_JVM).get("kind"));
    }

    @Test
    void 官方jvm对齐键值形态() {
        String out = """
                RUNTIME
                --------------------------------------------------------------------------------------------------------
                 MACHINE-NAME                   37@ff267334bb65
                 JVM-START-TIME                 2020-07-23 07:50:36
                GC
                --------------------------------------------------------------------------------------------------------
                 gc.ps_scavenge_count           12
                 gc.ps_marksweep_count          2""";
        JvmView v = ResultParsers.parseJvm(out);
        assertNotNull(v);
        assertEquals(14, v.gcTotalCount());
        assertEquals("2020-07-23 07:50:36", v.kv().get("JVM-START-TIME"));
    }

    @Test
    void monitor周期行解析() {
        List<MonitorPoint> pts = ResultParsers.parseMonitor(ARTHAS_MONITOR);
        assertEquals(2, pts.size());
        assertEquals(5, pts.get(0).total());
        assertEquals(42.29, pts.get(1).avgRtMs(), 1e-9);
        assertEquals(40.00, pts.get(1).failRatePct(), 1e-9);
        assertEquals("monitor", ResultParsers.parse("monitor -c 1 demo.MathGame primeFactors", ARTHAS_MONITOR).get("kind"));
    }

    @Test
    void 不可识别输出返回null_文本兜底() {
        assertNull(ResultParsers.parse("thread", "attach 失败：目标进程不可达"));
        assertNull(ResultParsers.parse("watch", "method=Foo.bar location=AtExit"));
        assertNull(ResultParsers.parse("sc -d", "com.demo.Target\nclass.loader= app"));
    }

    @Test
    void 标量指标提取供火山图对比() {
        Map<String, Double> s = ResultParsers.scalarMetrics("dashboard -n 1", MOCK_DASHBOARD);
        assertEquals(402.0, s.get("thread.total"), 1e-9);
        assertEquals(182.0, s.get("thread.waiting"), 1e-9);
        assertEquals(62.0, s.get("mem.heap.pct"), 1e-9);
        assertEquals(22.0, s.get("cpu.pct"), 1e-9);
        Map<String, Double> j = ResultParsers.scalarMetrics("jvm", MOCK_JVM);
        assertEquals(945.0, j.get("gc.total.count"), 1e-9);
        assertEquals(388.0, j.get("thread.total"), 1e-9);
    }

    @Test
    void welch检验_极值行为正确() {
        // 常数序列：方差为 0，均值不同 → 极显著；均值相同 → 不显著
        assertEquals(0.0, ResultParsers.welchPValue(new double[]{1, 1, 1}, new double[]{9, 9, 9}), 1e-6);
        assertEquals(1.0, ResultParsers.welchPValue(new double[]{5, 5}, new double[]{5, 5}), 1e-6);
        // 样本不足 → 不显著
        assertEquals(1.0, ResultParsers.welchPValue(new double[]{1}, new double[]{9, 9}), 1e-6);
        // 分离样本 → 显著（p < 0.05）
        assertTrue(ResultParsers.welchPValue(new double[]{10, 11, 12, 13}, new double[]{20, 21, 22, 23}) < 0.01);
    }
}
