package com.bianque.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void health返回UP状态() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.boot.info.BuildProperties> buildInfo = mock(ObjectProvider.class);
        when(buildInfo.getIfAvailable()).thenReturn(null);
        var result = new HealthController(buildInfo, "test-version", true).health();
        assertEquals("UP", result.get("status"));
        assertEquals("bianque-server", result.get("app"));
        assertEquals("test-version", result.get("version"));
    }
}
