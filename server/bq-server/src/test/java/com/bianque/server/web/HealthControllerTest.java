package com.bianque.server.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthControllerTest {

    @Test
    void health返回UP状态() {
        var result = new HealthController().health();
        assertEquals("UP", result.get("status"));
        assertEquals("bianque-server", result.get("app"));
    }
}
