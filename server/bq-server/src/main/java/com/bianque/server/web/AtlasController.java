package com.bianque.server.web;

import com.bianque.server.model.Types.AtlasReport;
import com.bianque.server.service.AtlasService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扁鹊·档案 API：运行时资产盘点 / 漏洞应急扫描。
 */
@RestController
@RequestMapping("/api/atlas")
public class AtlasController {

    public record ScanRequest(String targetClass) {
    }

    private final AtlasService atlas;

    public AtlasController(AtlasService atlas) {
        this.atlas = atlas;
    }

    @PostMapping("/scan")
    public AtlasReport scan(@RequestBody ScanRequest req, HttpServletRequest request) {
        String cls = req.targetClass() == null || req.targetClass().isBlank()
                ? "org.apache.logging.log4j.core.lookup.JndiLookup" : req.targetClass().trim();
        return atlas.scan(cls, DiagController.operator(request));
    }
}
