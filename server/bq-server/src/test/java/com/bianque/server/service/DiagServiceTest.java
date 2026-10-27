package com.bianque.server.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bianque.core.diag.DiagResult;
import com.bianque.server.exec.MockDiagExecutor;
import com.bianque.server.repo.AuditRepository;
import org.junit.jupiter.api.Test;

class DiagServiceTest {

    private final AuditRepository audit = new AuditRepository();
    private final DiagService service = new DiagService(new MockDiagExecutor(), audit, 30);

    @Test
    void 高危命令被拦截并留审计() {
        DiagResult r = service.execute("pod-3", "ognl '@System@exit(0)'", "人工");
        assertFalse(r.success());
        assertTrue(r.error().contains("已拦截"));
        assertFalse(audit.query("人工", null, 10).isEmpty());
        assertTrue(audit.query("人工", null, 10).get(0).result().equals("blocked"));
    }

    @Test
    void 观测命令正常执行并留审计() {
        DiagResult r = service.execute("pod-3", "thread -n 3", "人工");
        assertTrue(r.success());
        assertTrue(r.output().contains("线程总数"));
    }
}
