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
        DocsService docs = new DocsService(new FleetRepository(), "");
        assertTrue(docs.catalog().size() >= 70, "应索引全部官方文档页，实际=" + docs.catalog().size());
    }

    @Test
    void 搜索应命中命令与特性页() {
        DocsService docs = new DocsService(new FleetRepository(), "");
        List<DocsService.DocHit> hits = docs.search("tunnel", 10);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).slug().contains("tunnel"));
    }

    @Test
    void 静态页面路径可解析且防目录穿越() {
        DocsService docs = new DocsService(new FleetRepository(), "");
        Path ok = docs.resolvePage("doc/watch.html");
        assertTrue(ok.toAbsolutePath().normalize().toString().endsWith("watch.html"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> docs.resolvePage("../../materials/arthas-packaging-4.3.5-doc.zip"));
    }

    private static void assertFalse(boolean cond) {
        org.junit.jupiter.api.Assertions.assertFalse(cond);
    }
}
