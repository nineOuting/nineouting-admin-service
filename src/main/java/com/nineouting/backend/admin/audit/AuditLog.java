package com.nineouting.backend.admin.audit;

import com.nineouting.backend.admin.auth.AuthInterceptor;
import com.nineouting.backend.admin.auth.SessionTokens;
import com.nineouting.backend.admin.config.AdminProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * REQ-216 관리자가 한 일을 관리자 전용 DB(admin_db)에 남기고 화면에 보여 준다. 로그(admin.audit)에도 같이 남긴다.
 * 표는 처음 뜰 때 없으면 만든다 (표 하나라 Flyway는 쓰지 않는다). 기록하다 실패해도 하던 일은 막지 않는다 (로그에는 남음).
 * 비밀번호, 토큰 같은 열은 값을 가린다.
 */
@Component
public class AuditLog implements DisposableBean {

    /** 허용 IP 확인에서 찾은 접속 IP (IpAccessInterceptor가 넣는다) */
    public static final String CLIENT_IP_ATTRIBUTE = "adminClientIp";
    private static final Logger log = LoggerFactory.getLogger("admin.audit");
    private static final Pattern SECRET_COLUMN = Pattern.compile("password|secret|token", Pattern.CASE_INSENSITIVE);
    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JsonMapper json;
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    public AuditLog(AdminProperties properties, JsonMapper json) {
        this.json = json;
        AdminProperties.Database db = properties.auditDatabase();
        if (db == null) {
            dataSource = null;
            jdbc = null;
            return;
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("admin-audit");
        config.setJdbcUrl(db.url());
        config.setUsername(db.username());
        config.setPassword(db.password());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionInitSql("SET statement_timeout = '15s'");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table if not exists admin_audit_log (
                  id bigint generated always as identity primary key,
                  occurred_at timestamptz not null default now(),
                  actor varchar(100),
                  client_ip varchar(64),
                  action varchar(40) not null,
                  target varchar(300),
                  succeeded boolean not null,
                  detail jsonb
                );
                create index if not exists admin_audit_log_occurred_at on admin_audit_log (occurred_at desc);
                """);
    }

    /** 감사 기록 한 줄. detail은 변경 전과 후, 실패 이유 등 */
    public void record(String action, String target, boolean succeeded, Map<String, ?> detail) {
        HttpServletRequest request = currentRequest();
        String actor = actor(request);
        String ip = request == null ? null : (String) request.getAttribute(CLIENT_IP_ATTRIBUTE);
        String detailJson = detail == null ? null : json.writeValueAsString(detail);
        log.info("admin {} target={} succeeded={} actor={} ip={} detail={}", action, target, succeeded, actor, ip,
                detailJson);
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("insert into admin_audit_log (actor, client_ip, action, target, succeeded, detail)"
                    + " values (?, ?, ?, ?, ?, ?::jsonb)", actor, ip, action, truncate(target, 300), succeeded, detailJson);
        } catch (DataAccessException e) {
            log.error("감사 기록을 DB에 남기지 못했습니다 (action={}, target={})", action, target, e);
        }
    }

    /** 비밀번호, 토큰 같은 열은 *** 로 */
    public static Map<String, String> mask(Map<String, String> row) {
        if (row == null) {
            return null;
        }
        Map<String, String> masked = new LinkedHashMap<>();
        row.forEach((k, v) -> masked.put(k, v != null && isSecretColumn(k) ? "***" : v));
        return masked;
    }

    public record Entry(long id, String occurredAt, String actor, String clientIp, String action, String target,
                        boolean succeeded, String detail) {
    }

    public record Page(List<Entry> entries, long total, int page, int size, boolean enabled) {
    }

    /** 최신순. action을 주면 그 일만 */
    public Page page(int page, int size, String action) {
        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        if (jdbc == null) {
            return new Page(List.of(), 0, pageNumber, pageSize, false);
        }
        List<Object> args = new ArrayList<>();
        String where = "";
        if (action != null && !action.isBlank()) {
            where = " where action = ?";
            args.add(action);
        }
        long total = jdbc.queryForObject("select count(*) from admin_audit_log" + where, Long.class, args.toArray());
        args.add(pageSize);
        args.add((long) pageNumber * pageSize);
        List<Entry> entries = jdbc.query("select id, occurred_at, actor, client_ip, action, target, succeeded, detail::text"
                        + " from admin_audit_log" + where + " order by id desc limit ? offset ?",
                (rs, i) -> new Entry(rs.getLong("id"), format(rs.getTimestamp("occurred_at")), rs.getString("actor"),
                        rs.getString("client_ip"), rs.getString("action"), rs.getString("target"),
                        rs.getBoolean("succeeded"), rs.getString("detail")),
                args.toArray());
        return new Page(entries, total, pageNumber, pageSize, true);
    }

    private static String format(Timestamp timestamp) {
        return timestamp.toInstant().atZone(ZONE).format(TIME);
    }

    private static String actor(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object session = request.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE);
        return session instanceof SessionTokens.Session s ? s.username() : null;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void destroy() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /** 비밀번호, 토큰 같은 값을 담는 열 (기록에서 가리고, 값 추천에서 뺀다) */
    public static boolean isSecretColumn(String column) {
        return SECRET_COLUMN.matcher(column).find();
    }
}
