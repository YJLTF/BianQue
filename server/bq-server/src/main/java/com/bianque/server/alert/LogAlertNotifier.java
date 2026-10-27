package com.bianque.server.alert;

import com.bianque.server.model.Types.Alert;
import com.bianque.server.model.Types.RescueReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认通知器：仅日志留痕（未配置 webhook/自有实现时的兜底），由 AlertNotifierConfig 条件装配 */
public class LogAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(LogAlertNotifier.class);

    @Override
    public void onAlert(Alert alert) {
        log.info("[告警] {} {} {} {} — {}", alert.alertId(), alert.severity(),
                alert.appId() + "/" + alert.instanceId(), alert.status(), alert.message());
    }

    @Override
    public void onRescueCompleted(Alert alert, RescueReport report) {
        log.info("[取证完成] {} → {}", alert.alertId(), report.conclusion());
    }
}
