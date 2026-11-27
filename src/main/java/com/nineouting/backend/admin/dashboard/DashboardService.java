package com.nineouting.backend.admin.dashboard;

import com.nineouting.backend.admin.db.DatabaseRegistry;
import com.nineouting.backend.admin.db.DatabaseRegistry.Target;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * REQ-215 관리자 현황. 서비스 DB에서 읽기만 한다. DB 하나가 안 되면 그 칸만 비우고 나머지는 보여 준다.
 * 서비스들은 한국 시간을 시간대 없이 저장하므로 "오늘", "6시간 전"도 한국 시간으로 계산한다.
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    /** 진행 중으로 이만큼 넘게 남으면 멈춘 회의로 본다 */
    static final int STUCK_HOURS = 6;
    private static final int LIST_LIMIT = 20;
    private static final String NOW = "(now() at time zone 'Asia/Seoul')";
    private static final String TODAY = "date_trunc('day', " + NOW + ")";

    private final DatabaseRegistry registry;

    public DashboardService(DatabaseRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param counts        숫자 요약 (users, usersToday, workspaces, meetingsToday, meetingsInProgress)
     * @param jobs          처리 종류별 상태 건수 (draft, minutesRag, materialRag, mail)
     * @param stuckMeetings 진행 중으로 stuckHours 넘게 남은 회의
     * @param failures      최근 실패 (종류마다 최대 20개)
     * @param unavailable   읽지 못한 DB
     */
    public record Dashboard(Map<String, Long> counts, Map<String, Map<String, Long>> jobs,
                            List<Map<String, Object>> stuckMeetings, List<Map<String, Object>> failures,
                            List<String> unavailable, int stuckHours) {
    }

    public Dashboard load() {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Map<String, Long>> jobs = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();

        read("user", unavailable, jdbc -> {
            counts.put("users", count(jdbc, "select count(*) from users"));
            counts.put("usersToday", count(jdbc, "select count(*) from users where created_at >= " + TODAY));
            return true;
        });
        read("workspace", unavailable, jdbc -> {
            counts.put("workspaces", count(jdbc, "select count(*) from workspaces"));
            return true;
        });
        List<Map<String, Object>> stuck = read("meeting", unavailable, jdbc -> {
            counts.put("meetingsToday", count(jdbc, "select count(*) from meetings where deleted_at is null"
                    + " and status <> 'CANCELLED' and start_at >= " + TODAY
                    + " and start_at < " + TODAY + " + interval '1 day'"));
            counts.put("meetingsInProgress", count(jdbc,
                    "select count(*) from meetings where deleted_at is null and status = 'IN_PROGRESS'"));
            jobs.put("materialRag", byStatus(jdbc, "select rag_status, count(*) from meeting_materials"
                    + " where rag_status is not null group by rag_status"));
            failures.addAll(jdbc.queryForList("""
                    select 'materialRag' as kind, id, file_name as title, rag_error_code as error,
                           to_char(rag_sent_at, 'YYYY-MM-DD HH24:MI') as at, meeting_id
                    from meeting_materials where rag_status = 'FAILED' order by id desc limit ?""", LIST_LIMIT));
            return jdbc.queryForList("""
                    select id, title, workspace_id, writer_user_id,
                           to_char(started_at, 'YYYY-MM-DD HH24:MI') as started_at,
                           floor(extract(epoch from (%s - started_at)) / 3600)::int as hours
                    from meetings
                    where deleted_at is null and status = 'IN_PROGRESS'
                      and started_at < %s - make_interval(hours => ?)
                    order by started_at limit ?""".formatted(NOW, NOW), STUCK_HOURS, LIST_LIMIT);
        }).orElse(List.of());
        read("minutes", unavailable, jdbc -> {
            jobs.put("draft", byStatus(jdbc, "select draft_status, count(*) from minutes"
                    + " where trashed_at is null and draft_status is not null group by draft_status"));
            jobs.put("minutesRag", byStatus(jdbc, "select rag_status, count(*) from minutes"
                    + " where trashed_at is null and rag_status is not null group by rag_status"));
            failures.addAll(jdbc.queryForList("""
                    select 'draft' as kind, id, title, draft_error as error,
                           to_char(draft_requested_at, 'YYYY-MM-DD HH24:MI') as at, meeting_id
                    from minutes where trashed_at is null and draft_status = 'FAILED' and status = 'WRITING'
                    order by id desc limit ?""", LIST_LIMIT));
            failures.addAll(jdbc.queryForList("""
                    select 'minutesRag' as kind, id, title, rag_error_code as error,
                           to_char(rag_sent_at, 'YYYY-MM-DD HH24:MI') as at, meeting_id
                    from minutes where trashed_at is null and rag_status = 'FAILED' order by id desc limit ?""",
                    LIST_LIMIT));
            return true;
        });
        read("notification", unavailable, jdbc -> {
            jobs.put("mail", byStatus(jdbc, "select status, count(*) from outgoing_mails group by status"));
            failures.addAll(jdbc.queryForList("""
                    select 'mail' as kind, id, kind as title, left(last_error, 300) as error,
                           to_char(created_at, 'YYYY-MM-DD HH24:MI') as at
                    from outgoing_mails where status = 'FAILED' order by id desc limit ?""", LIST_LIMIT));
            return true;
        });
        return new Dashboard(counts, jobs, stuck, failures, unavailable, STUCK_HOURS);
    }

    /** 설정에 없거나 읽다가 실패한 DB는 unavailable에 넣고 비운다 */
    private <T> Optional<T> read(String name, List<String> unavailable, Function<JdbcTemplate, T> query) {
        Optional<Target> target = registry.all().stream().filter(t -> t.name().equals(name)).findFirst();
        if (target.isEmpty()) {
            unavailable.add(name);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(query.apply(target.get().jdbc()));
        } catch (DataAccessException e) {
            log.warn("현황을 읽지 못했습니다 (db={}): {}", name, e.getMostSpecificCause().getMessage());
            unavailable.add(name);
            return Optional.empty();
        }
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static Map<String, Long> byStatus(JdbcTemplate jdbc, String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            result.put(rs.getString(1), rs.getLong(2));
        });
        return result;
    }
}
