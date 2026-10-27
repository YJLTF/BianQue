package com.bianque.server.alert;

import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;

/**
 * 告警通知 SPI（出站预留）：平台不绑定任何 IM；接入自有告警系统时实现本接口注册为 Spring Bean，
 * 或直接启用内置 WebhookAlertNotifier（bianque.alerts.webhook-url）。
 */
public interface AlertNotifier {

    /** 新告警进入平台 */
    void onAlert(Alert alert);

    /** 取证完成（现场报告已生成） */
    void onRescueCompleted(Alert alert, com.bianque.server.model.Types.RescueReport report);
}
