package com.bianque.server.web;

import com.bianque.server.service.TemplateService;
import com.bianque.server.service.TemplateService.TemplateReq;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板中心 API（/api/templates）：读需登录态；写（POST/PUT/DELETE）经 AuthInterceptor
 * 统一要求 diag:execute——诊断台「一键沉淀」即操作员可用；每次变更入审计。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templates;

    public TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    /** 诊断台可用列表（仅启用） */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String kind) {
        return templates.listEnabled(kind);
    }

    /** 管理列表（含停用） */
    @GetMapping("/all")
    public List<Map<String, Object>> listAll(@RequestParam(required = false) String kind) {
        return templates.listAll(kind);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody TemplateReq req, HttpServletRequest request) {
        return templates.create(req, DiagController.operator(request));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody TemplateReq req,
                                      HttpServletRequest request) {
        return templates.update(id, req, DiagController.operator(request));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable long id, @RequestBody Map<String, Boolean> body,
                                      HttpServletRequest request) {
        return templates.enable(id, Boolean.TRUE.equals(body.get("enabled")), DiagController.operator(request));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
        return templates.delete(id, DiagController.operator(request));
    }
}
