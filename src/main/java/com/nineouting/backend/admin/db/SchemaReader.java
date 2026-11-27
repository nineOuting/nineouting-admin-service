package com.nineouting.backend.admin.db;

import com.nineouting.backend.admin.error.AdminException;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * public 스키마의 표, 열, 기본 키를 DB 카탈로그에서 읽는다.
 * 표와 열 이름은 여기서 읽은 것만 SQL에 넣는다 (화면이 보낸 이름을 그대로 쓰지 않는다).
 */
@Component
public class SchemaReader {

    /** 기본 키가 있어도 읽기만 하는 표. Flyway 이력을 고치면 다음 배포가 실패한다 */
    static final Set<String> READ_ONLY_TABLES = Set.of("flyway_schema_history");

    private static final String TABLES = """
            select c.relname as name, greatest(c.reltuples, 0)::bigint as estimate
            from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'public' and c.relkind in ('r', 'p')
            order by c.relname""";

    private static final String COLUMNS = """
            select a.attname as name, format_type(a.atttypid, a.atttypmod) as type, not a.attnotnull as nullable,
                   pg_get_expr(d.adbin, d.adrelid) as default_value, a.attidentity as identity, a.attgenerated as generated
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace
            left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
            where n.nspname = 'public' and c.relname = ? and a.attnum > 0 and not a.attisdropped
            order by a.attnum""";

    private static final String PRIMARY_KEY = """
            select a.attname
            from pg_index i
            join pg_class c on c.oid = i.indrelid
            join pg_namespace n on n.oid = c.relnamespace
            join unnest(i.indkey) with ordinality as k(attnum, ord) on true
            join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
            where n.nspname = 'public' and c.relname = ? and i.indisprimary
            order by k.ord""";

    public record TableSummary(String name, long estimatedRows) {
    }

    /**
     * @param writable 추가, 수정 때 값을 넣을 수 있는지 (항상 생성되는 identity, 계산 열은 못 넣음)
     */
    public record Column(String name, String type, boolean nullable, String defaultValue, boolean primaryKey,
                         boolean writable) {
    }

    public record Table(String name, List<Column> columns, List<String> primaryKey, boolean readOnly,
                        String readOnlyReason) {

        public Column column(String name) {
            return columns.stream().filter(c -> c.name().equals(name)).findFirst()
                    .orElseThrow(() -> AdminException.badRequest("열을 찾을 수 없습니다: " + name));
        }
    }

    public List<TableSummary> tables(JdbcTemplate jdbc) {
        return jdbc.query(TABLES, (rs, i) -> new TableSummary(rs.getString("name"), rs.getLong("estimate")));
    }

    public Table table(JdbcTemplate jdbc, String name) {
        List<String> primaryKey = jdbc.queryForList(PRIMARY_KEY, String.class, name);
        List<Column> columns = jdbc.query(COLUMNS, (rs, i) -> {
            String column = rs.getString("name");
            boolean alwaysIdentity = "a".equals(rs.getString("identity"));
            boolean generated = rs.getString("generated") != null && !rs.getString("generated").isEmpty();
            return new Column(column, rs.getString("type"), rs.getBoolean("nullable"), rs.getString("default_value"),
                    primaryKey.contains(column), !alwaysIdentity && !generated);
        }, name);
        if (columns.isEmpty()) {
            throw AdminException.notFound("표를 찾을 수 없습니다: " + name);
        }
        String reason = null;
        if (READ_ONLY_TABLES.contains(name)) {
            reason = "배포 이력 표라 읽기만 할 수 있습니다";
        } else if (primaryKey.isEmpty()) {
            reason = "기본 키가 없어 한 행을 골라 고칠 수 없습니다";
        }
        return new Table(name, columns, primaryKey, reason != null, reason);
    }
}
