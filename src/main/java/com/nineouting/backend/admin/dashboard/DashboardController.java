package com.nineouting.backend.admin.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** REQ-215 관리자 현황 */
@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/admin/dashboard")
    public DashboardService.Dashboard dashboard() {
        return dashboard.load();
    }
}
