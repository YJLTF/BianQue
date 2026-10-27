package com.bianque.core.exec;

import com.bianque.core.diag.DiagCommand;
import com.bianque.core.diag.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsShCommandLineTest {

    @Test
    void 渲染非交互命令行() {
        DiagCommand c = new DiagCommand("order-app", "pod-3", "trace", RiskLevel.MID,
                "trace com.bianque.OrderService placeOrder '#cost>100'", 60);
        assertEquals(
                "as.sh --select order-app --command \"trace com.bianque.OrderService placeOrder '#cost>100'\" --execution-timeout 60",
                AsShCommandLine.render(c, null));
    }

    @Test
    void 指定进程覆盖应用名() {
        DiagCommand c = new DiagCommand("order-app", "pod-3", "thread", RiskLevel.LOW, "thread -n 3", 30);
        assertEquals("as.sh --select app.jar --command \"thread -n 3\" --execution-timeout 30",
                AsShCommandLine.render(c, "app.jar"));
    }
}
