package com.nineouting.backend.admin.beta;

import com.nineouting.backend.admin.error.AdminException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 베타 테스트 개발팀 집계 (설계서 5장 D1~D9 중 지금 기록으로 셀 수 있는 것). 개수만 돌려준다 */
@RestController
public class BetaMetricsController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int DEFAULT_DAYS = 30;
    private static final int MAX_DAYS = 366;

    private final BetaMetricsService metrics;
    private final Clock clock;

    public BetaMetricsController(BetaMetricsService metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param from    시작 날짜 (그날 포함, 비우면 30일 전)
     * @param to      끝 날짜 (그날 포함, 비우면 오늘)
     * @param exclude 뺄 워크스페이스 번호 (쉼표로, 우리 팀 내부 테스트)
     */
    @GetMapping("/api/admin/beta-metrics")
    public BetaMetricsService.Report metrics(@RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String exclude) {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate end = date(to, today);
        LocalDate start = date(from, end.minusDays(DEFAULT_DAYS - 1L));
        if (start.isAfter(end)) {
            throw AdminException.badRequest("시작 날짜가 끝 날짜보다 늦습니다");
        }
        if (start.plusDays(MAX_DAYS).isBefore(end)) {
            throw AdminException.badRequest("기간은 1년까지 볼 수 있습니다");
        }
        return metrics.load(new BetaMetricsService.Query(start, end, workspaces(exclude)));
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw AdminException.badRequest("날짜는 YYYY-MM-DD로 적어 주세요: " + value);
        }
    }

    private static Set<Long> workspaces(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        try {
            return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(Long::valueOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (NumberFormatException e) {
            throw AdminException.badRequest("뺄 워크스페이스는 번호를 쉼표로 적어 주세요");
        }
    }
}
