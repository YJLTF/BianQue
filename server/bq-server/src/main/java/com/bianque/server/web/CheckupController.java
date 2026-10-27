package com.bianque.server.web;

import com.bianque.server.model.Types.CheckupRow;
import com.bianque.server.service.CheckupService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扁鹊·体检 API：巡检运行（落库）与最近一次结果回放、运行历史。
 */
@RestController
@RequestMapping("/api/checkup")
public class CheckupController {

    private final CheckupService checkup;

    public CheckupController(CheckupService checkup) {
        this.checkup = checkup;
    }

    @PostMapping("/run")
    public Map<String, Object> run(HttpServletRequest request) {
        List<CheckupRow> rows = checkup.runAll(DiagController.operator(request));
        return summarize(rows);
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() {
        return summarize(checkup.latest());
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return checkup.history();
    }

    private static Map<String, Object> summarize(List<CheckupRow> rows) {
        double avg = rows.stream().mapToInt(CheckupRow::score).average().orElse(0);
        return Map.of(
                "rows", rows,
                "avgScore", Math.round(avg * 10) / 10.0,
                "instanceCount", rows.size());
    }
}
