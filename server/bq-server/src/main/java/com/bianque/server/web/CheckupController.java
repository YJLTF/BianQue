package com.bianque.server.web;

import com.bianque.server.model.Types.CheckupRow;
import com.bianque.server.service.CheckupService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扁鹊·体检 API：巡检运行与健康分。
 */
@RestController
@RequestMapping("/api/checkup")
public class CheckupController {

    private final CheckupService checkup;

    public CheckupController(CheckupService checkup) {
        this.checkup = checkup;
    }

    @PostMapping("/run")
    public List<CheckupRow> run() {
        return checkup.runAll();
    }

    @GetMapping("/latest")
    public List<CheckupRow> latest() {
        return checkup.latest();
    }
}
