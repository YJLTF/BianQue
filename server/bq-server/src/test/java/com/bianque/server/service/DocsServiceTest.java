package com.bianque.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.server.repo.FleetRepository;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocsServiceTest {

    @Test
    void 目录索引覆盖全部文档页() {
        DocsService docs = new DocsService(new FleetRepository(null), "");
        assertTrue(docs.catalog().size() >= 70, "应索引全部官方文档页，实际=" + docs.catalog().size());
    }

    @Test
    void 搜索应命中命令与特性页() {
        DocsService docs = new DocsService(new FleetRepository(null), "");
        List<DocsService.DocHit> hits = docs.search("tunnel", 10);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).slug().contains("tunnel"));
    }

    @Test
    void 知识库检索_英文命令词与中文问题都能命中() {
        DocsService docs = new DocsService(new FleetRepository(null), "");
        List<DocsService.DocHit> en = docs.searchKnowledge("watch 命令怎么用？观察方法出参有什么限制？", 4);
        assertTrue(!en.isEmpty(), "ASCII 命令词应命中");
        assertEquals("watch", en.get(0).slug(), "watch 页应排首位");

        List<DocsService.DocHit> zh = docs.searchKnowledge("线程飙高，CPU 占用过高怎么排查？", 4);
        assertTrue(zh.stream().anyMatch(h -> h.slug().equals("thread")), "中文问题应命中 thread 页，实际="
                + zh.stream().map(DocsService.DocHit::slug).toList());
    }

    @Test
    void 知识库检索_无关或空白问题零命中() {
        DocsService docs = new DocsService(new FleetRepository(null), "");
        assertTrue(docs.searchKnowledge("今天天气怎么样", 4).isEmpty());
        assertTrue(docs.searchKnowledge("", 4).isEmpty());
        assertTrue(docs.searchKnowledge(null, 4).isEmpty());
    }

    @Test
    void 静态页面路径可解析且防目录穿越() {
        DocsService docs = new DocsService(new FleetRepository(null), "");
        Path ok = docs.resolvePage("doc/watch.html");
        assertTrue(ok.toAbsolutePath().normalize().toString().endsWith("watch.html"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> docs.resolvePage("../../materials/arthas-packaging-4.3.5-doc.zip"));
    }

    private static void assertFalse(boolean cond) {
        org.junit.jupiter.api.Assertions.assertFalse(cond);
    }
}
