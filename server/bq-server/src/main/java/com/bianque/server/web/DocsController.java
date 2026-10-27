package com.bianque.server.web;

import com.bianque.server.service.DocsService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档中心 API：目录、全文搜索（构建期索引 + 服务端打分）。
 */
@RestController
@RequestMapping("/api/docs")
public class DocsController {

    private final DocsService docs;

    public DocsController(DocsService docs) {
        this.docs = docs;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of(
                "version", "4.3.5",
                "total", docs.catalog().size(),
                "pages", docs.catalog());
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q,
                                      @RequestParam(defaultValue = "20") int limit) {
        List<DocsService.DocHit> hits = docs.search(q, limit);
        return Map.of("query", q, "total", hits.size(), "hits", hits);
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        return Map.of("indexed", docs.reload());
    }
}
