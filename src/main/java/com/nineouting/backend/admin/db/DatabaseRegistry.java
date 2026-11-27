package com.nineouting.backend.admin.db;

import com.nineouting.backend.admin.config.AdminProperties;
import com.nineouting.backend.admin.error.AdminException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설정에 있는 DB마다 작은 연결 풀을 만든다. 관리자 한 명이 쓰므로 DB마다 연결 3개면 충분하다.
 * 쿼리 하나가 15초를 넘으면 DB가 끊는다 (큰 표에서 실수로 오래 도는 일 방지).
 */
@Component
public class DatabaseRegistry implements DisposableBean {

    private static final int POOL_SIZE = 3;
    private static final String STATEMENT_TIMEOUT = "SET statement_timeout = '15s'";

    private final Map<String, Target> targets = new LinkedHashMap<>();

    public DatabaseRegistry(AdminProperties properties) {
        properties.databases().forEach((name, db) -> {
            HikariConfig config = new HikariConfig();
            config.setPoolName("admin-" + name);
            config.setJdbcUrl(db.url());
            config.setUsername(db.username());
            config.setPassword(db.password());
            config.setMaximumPoolSize(POOL_SIZE);
            config.setMinimumIdle(0);
            config.setConnectionInitSql(STATEMENT_TIMEOUT);
            // DB 하나가 안 떠 있어도 서비스는 뜬다. 그 DB를 고를 때 오류가 난다
            config.setInitializationFailTimeout(-1);
            HikariDataSource dataSource = new HikariDataSource(config);
            targets.put(name, new Target(name, databaseName(db.url()), new JdbcTemplate(dataSource),
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), dataSource));
        });
    }

    public record Target(String name, String database, JdbcTemplate jdbc, TransactionTemplate transaction,
                         HikariDataSource dataSource) {
    }

    public List<Target> all() {
        return List.copyOf(targets.values());
    }

    public Target require(String name) {
        Target target = targets.get(name);
        if (target == null) {
            throw AdminException.notFound("DB를 찾을 수 없습니다: " + name);
        }
        return target;
    }

    /** jdbc:postgresql://host:5432/user_db?x=y → user_db */
    static String databaseName(String url) {
        String path = url.substring(url.lastIndexOf('/') + 1);
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    @Override
    public void destroy() {
        targets.values().forEach(target -> target.dataSource().close());
    }
}
