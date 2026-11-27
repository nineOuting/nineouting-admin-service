package com.nineouting.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nineouting.backend.admin.auth.AuthInterceptor;
import com.nineouting.backend.admin.auth.LoginGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * REQ-215 현황, REQ-216 감사 기록, REQ-217 운영 조치. 서비스 DB 5개를 PostgreSQL 하나로 대신하고
 * meeting-service, minutes-service는 가짜 서버로 둔다
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardAuditOperationsTest {

    private static final String USERNAME = "tester";
    private static final String PASSWORD = "test-password";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final HttpServer SERVICES;
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LoginGuard guard;

    static {
        POSTGRES.start();
        execute("""
                create table users (id bigint primary key, email varchar(100), password_hash varchar(100),
                  created_at timestamp not null);
                create table workspaces (id bigint primary key, created_at timestamp not null);
                create table meetings (id bigint primary key, title varchar(100), workspace_id bigint,
                  writer_user_id bigint, status varchar(20) not null, start_at timestamp not null,
                  started_at timestamp, deleted_at timestamp);
                create table meeting_materials (id bigint primary key, meeting_id bigint, file_name varchar(100),
                  rag_status varchar(20), rag_error_code varchar(50), rag_sent_at timestamp);
                create table minutes (id bigint primary key, meeting_id bigint, title varchar(100),
                  status varchar(20) not null, draft_status varchar(20), draft_error varchar(100),
                  draft_requested_at timestamp, rag_status varchar(20), rag_error_code varchar(50),
                  rag_sent_at timestamp, trashed_at timestamp);
                create table outgoing_mails (id bigint primary key, kind varchar(50), status varchar(20) not null,
                  last_error varchar(500), created_at timestamp not null);
                insert into users values
                  (1, 'a@example.com', 'hash', now() at time zone 'Asia/Seoul'),
                  (2, 'b@example.com', 'hash', now() at time zone 'Asia/Seoul' - interval '3 days');
                insert into workspaces values (1, now());
                insert into meetings values
                  (10, '멈춘 회의', 1, 1, 'IN_PROGRESS', now() at time zone 'Asia/Seoul',
                       now() at time zone 'Asia/Seoul' - interval '8 hours', null),
                  (11, '방금 시작한 회의', 1, 1, 'IN_PROGRESS', now() at time zone 'Asia/Seoul',
                       now() at time zone 'Asia/Seoul' - interval '1 hour', null),
                  (12, '지운 회의', 1, 1, 'IN_PROGRESS', now() at time zone 'Asia/Seoul',
                       now() at time zone 'Asia/Seoul' - interval '9 hours', now());
                insert into meeting_materials values (20, 10, '스캔.pdf', 'FAILED', 'no_text', now()),
                  (21, 10, '예산.xlsx', 'READY', null, now());
                insert into minutes values
                  (30, 10, '실패한 초안', 'WRITING', 'FAILED', 'LLM_TIMEOUT', now(), null, null, null, null),
                  (31, 11, '실패한 검색 반영', 'CONFIRMED', 'SUCCEEDED', null, now(), 'FAILED', 'rejected', now(), null),
                  (32, 11, '쓰레기통', 'WRITING', 'FAILED', 'X', now(), null, null, null, now());
                insert into outgoing_mails values (40, 'INVITATION', 'FAILED', 'smtp down', now()),
                  (41, 'INVITATION', 'SENT', null, now());
                """);
        try {
            SERVICES = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SERVICES.createContext("/", DashboardAuditOperationsTest::handle);
        SERVICES.start();
    }

    /** 회의 10은 마무리되고, 그 밖은 진행 중이 아니라는 409 */
    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        CALLS.add(exchange.getRequestMethod() + " " + path);
        if (path.equals("/internal/admin/meetings/10/finish") || path.equals("/internal/admin/minutes/30/draft-retry")) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            byte[] body = "{\"success\":false,\"data\":null,\"message\":\"진행 중인 회의가 아닙니다\",\"code\":\"MEETING_NOT_IN_PROGRESS\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nineouting.admin.username", () -> USERNAME);
        registry.add("nineouting.admin.password-hash", () -> new BCryptPasswordEncoder().encode(PASSWORD));
        registry.add("nineouting.admin.session-secret", () -> "test-session-secret-0123456789-abcdef");
        registry.add("nineouting.admin.cookie-secure", () -> "false");
        registry.add("nineouting.admin.cloudfront-ranges-url", () -> "");
        for (String db : List.of("user", "workspace", "meeting", "minutes", "notification")) {
            database(registry, "nineouting.admin.databases." + db);
        }
        database(registry, "nineouting.admin.audit-database");
        String services = "http://127.0.0.1:" + SERVICES.getAddress().getPort();
        registry.add("nineouting.admin.services.meeting-url", () -> services);
        registry.add("nineouting.admin.services.minutes-url", () -> services);
    }

    private static void database(DynamicPropertyRegistry registry, String prefix) {
        registry.add(prefix + ".url", POSTGRES::getJdbcUrl);
        registry.add(prefix + ".username", POSTGRES::getUsername);
        registry.add(prefix + ".password", POSTGRES::getPassword);
    }

    @BeforeEach
    void resetLoginGuard() {
        guard.succeeded();
    }

    @Test
    void 현황은_숫자와_처리_상태와_멈춘_회의와_실패_목록을_보여_준다() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/dashboard").cookie(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.users").value(2))
                .andExpect(jsonPath("$.counts.usersToday").value(1))
                .andExpect(jsonPath("$.counts.workspaces").value(1))
                .andExpect(jsonPath("$.counts.meetingsInProgress").value(2))
                .andExpect(jsonPath("$.jobs.draft.FAILED").value(1))
                .andExpect(jsonPath("$.jobs.minutesRag.FAILED").value(1))
                .andExpect(jsonPath("$.jobs.materialRag.READY").value(1))
                .andExpect(jsonPath("$.jobs.mail.FAILED").value(1))
                // 6시간 넘게 진행 중인 회의만, 지운 회의는 빼고
                .andExpect(jsonPath("$.stuckMeetings[*].id", contains(10)))
                .andExpect(jsonPath("$.stuckMeetings[0].hours").value(8))
                // 쓰레기통의 회의록은 빼고, 작성 중인 회의록의 실패한 초안만
                .andExpect(jsonPath("$.failures[?(@.kind == 'draft')].id", contains(30)))
                .andExpect(jsonPath("$.failures[?(@.kind == 'minutesRag')].id", contains(31)))
                .andExpect(jsonPath("$.failures[?(@.kind == 'materialRag')].id", contains(20)))
                .andExpect(jsonPath("$.failures[?(@.kind == 'mail')].error", contains("smtp down")))
                .andExpect(jsonPath("$.unavailable").isEmpty());
    }

    @Test
    void 운영_조치는_서비스_내부_기능을_부르고_서비스의_오류를_그대로_보여_준다() throws Exception {
        Cookie cookie = loggedIn();
        mockMvc.perform(write(post("/api/admin/operations/meetings/10/finish")).cookie(cookie))
                .andExpect(status().isNoContent());
        mockMvc.perform(write(post("/api/admin/operations/minutes/30/draft-retry")).cookie(cookie))
                .andExpect(status().isNoContent());
        mockMvc.perform(write(post("/api/admin/operations/meetings/11/finish")).cookie(cookie))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEETING_NOT_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("진행 중인 회의가 아닙니다"));
        assertThat(CALLS).contains("POST /internal/admin/meetings/10/finish", "POST /internal/admin/minutes/30/draft-retry",
                "POST /internal/admin/meetings/11/finish");
        // 관리자 화면 머리글이 없으면 부르지 않는다
        mockMvc.perform(post("/api/admin/operations/meetings/10/finish").cookie(cookie)).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/audit?action=meeting.finish").cookie(cookie))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.entries[0].target").value("meeting#11"))
                .andExpect(jsonPath("$.entries[0].succeeded").value(false))
                .andExpect(jsonPath("$.entries[0].detail", containsString("MEETING_NOT_IN_PROGRESS")))
                .andExpect(jsonPath("$.entries[1].target").value("meeting#10"))
                .andExpect(jsonPath("$.entries[1].succeeded").value(true))
                .andExpect(jsonPath("$.entries[1].actor").value(USERNAME))
                .andExpect(jsonPath("$.entries[1].clientIp").value("127.0.0.1"));
    }

    @Test
    void 로그인과_행_변경을_기록하고_비밀값은_가린다() throws Exception {
        mockMvc.perform(write(post("/api/admin/session")).content(login(USERNAME, "wrong")))
                .andExpect(status().isUnauthorized());
        Cookie cookie = loggedIn();
        mockMvc.perform(write(put("/api/admin/databases/user/tables/users/rows")).cookie(cookie)
                        .content("{\"key\":{\"id\":\"2\"},\"values\":{\"password_hash\":\"new-hash\",\"email\":\"c@example.com\"}}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/audit?action=row.update").cookie(cookie))
                .andExpect(jsonPath("$.entries[0].target").value("user.users"))
                .andExpect(jsonPath("$.entries[0].actor").value(USERNAME))
                .andExpect(jsonPath("$.entries[0].detail", containsString("c@example.com")))
                .andExpect(jsonPath("$.entries[0].detail", containsString("***")))
                .andExpect(jsonPath("$.entries[0].detail", org.hamcrest.Matchers.not(containsString("new-hash"))));
        mockMvc.perform(get("/api/admin/audit?action=login&size=2").cookie(cookie))
                .andExpect(jsonPath("$.entries[0].succeeded").value(true))
                .andExpect(jsonPath("$.entries[0].actor").value(USERNAME))
                .andExpect(jsonPath("$.entries[1].succeeded").value(false))
                .andExpect(jsonPath("$.entries[1].actor").doesNotExist());
        // 기록을 고치거나 지우는 주소는 없다
        mockMvc.perform(write(post("/api/admin/audit")).cookie(cookie)).andExpect(status().isMethodNotAllowed());
    }

    private Cookie loggedIn() throws Exception {
        return mockMvc.perform(write(post("/api/admin/session")).content(login(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(AuthInterceptor.COOKIE);
    }

    private static String login(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header(AuthInterceptor.REQUEST_HEADER, AuthInterceptor.REQUEST_HEADER_VALUE)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
