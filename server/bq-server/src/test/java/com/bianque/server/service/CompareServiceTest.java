package com.bianque.server.service;

import com.bianque.core.diag.DiagResult;
import com.bianque.server.service.CompareService.CompareReq;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 火山图对比失败可见性（线上事故固化）：
 * 曾把全部执行失败静默吞掉，回传 metrics=[]/insufficient=[] 的"正常空对比"，排查者无从得知原因——
 * 现在全部失败须快速失败并透传真实原因（如 attach 失败原文），部分失败须进 errors 清单。
 */
class CompareServiceTest {

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

    @Test
    void 全部命令失败_首轮即中止并透传真实原因() {
        DiagService diag = Mockito.mock(DiagService.class);
        when(diag.execute(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(DiagResult.fail("attach 失败：无法附加到目标进程 33870（进程是否存活、与节点同用户，"
                        + "且 arthas 发行目录对目标用户可读？）", 6500));
        CompareService svc = new CompareService(diag);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.compare(new CompareReq("i-t", "i-b", 3, 200), "tester"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        String reason = ex.getReason();
        assertNotNull(reason);
        assertTrue(reason.contains("全部无有效样本"), reason);
        assertTrue(reason.contains("attach 失败"), reason);
        assertTrue(reason.contains("「i-t」"), reason); // 失败条目须带实例与命令上下文
        // 首轮即中止：3 命令 × 2 实例 = 6 次执行，不空转剩余 2 轮
        Mockito.verify(diag, Mockito.times(6)).execute(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 部分命令失败_成功样本照常统计且失败清单可见() {
        DiagService diag = Mockito.mock(DiagService.class);
        when(diag.execute(anyString(), eq("memory"), anyString(), anyString()))
                .thenAnswer(inv -> DiagResult.ok(ARTHAS_MEMORY, 12));
        when(diag.execute(anyString(), eq("dashboard -n 1"), anyString(), anyString()))
                .thenReturn(DiagResult.fail("dashboard 暂不可用", 5));
        when(diag.execute(anyString(), eq("jvm"), anyString(), anyString()))
                .thenReturn(DiagResult.fail("jvm 暂不可用", 5));
        CompareService svc = new CompareService(diag);
        Map<String, Object> out = svc.compare(new CompareReq("i-t", "i-b", 2, 200), "tester");
        @SuppressWarnings("unchecked")
        List<CompareService.MetricStat> metrics = (List<CompareService.MetricStat>) out.get("metrics");
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) out.get("errors");
        // memory 每实例 2 轮各 1 个样本 → heap/old/meta 3 项指标全部 sufficient
        assertEquals(3, metrics.size());
        metrics.forEach(m -> {
            assertEquals(2, m.targetSamples().size());
            assertEquals(2, m.baseSamples().size());
            assertTrue(m.sufficient());
        });
        assertEquals(List.of(), out.get("insufficient"));
        // 失败清单：2 轮 × (dashboard + jvm) × 2 实例 = 8 条，带「实例」命令上下文
        assertEquals(8, errors.size());
        assertEquals("「i-t」dashboard -n 1: dashboard 暂不可用", errors.get(0));
    }
}
