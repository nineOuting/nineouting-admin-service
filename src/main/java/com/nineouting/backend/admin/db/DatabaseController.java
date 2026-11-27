package com.nineouting.backend.admin.db;

import com.nineouting.backend.admin.db.DatabaseRegistry.Target;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REQ-214 관리자 DB 탐색기 */
@RestController
@RequestMapping("/api/admin/databases")
public class DatabaseController {

    private final DatabaseRegistry registry;
    private final SchemaReader schema;
    private final RowService rows;

    public DatabaseController(DatabaseRegistry registry, SchemaReader schema, RowService rows) {
        this.registry = registry;
        this.schema = schema;
        this.rows = rows;
    }

    public record DatabaseResponse(String name, String database) {
    }

    public record InsertRequest(Map<String, String> values) {
    }

    public record UpdateRequest(Map<String, String> key, Map<String, String> values) {
    }

    public record DeleteRequest(Map<String, String> key) {
    }

    @GetMapping
    public List<DatabaseResponse> databases() {
        return registry.all().stream().map(t -> new DatabaseResponse(t.name(), t.database())).toList();
    }

    @GetMapping("/{db}/tables")
    public List<SchemaReader.TableSummary> tables(@PathVariable String db) {
        return schema.tables(registry.require(db).jdbc());
    }

    @GetMapping("/{db}/tables/{table}")
    public SchemaReader.Table table(@PathVariable String db, @PathVariable String table) {
        return schema.table(registry.require(db).jdbc(), table);
    }

    /** REQ-219 짧은 글자 열에 이미 들어 있는 값 (열 이름 → 값 목록) */
    @GetMapping("/{db}/tables/{table}/suggestions")
    public Map<String, List<String>> suggestions(@PathVariable String db, @PathVariable String table) {
        return rows.suggestions(registry.require(db), table);
    }

    @GetMapping("/{db}/tables/{table}/rows")
    public RowService.Page rows(@PathVariable String db, @PathVariable String table,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size,
                                @RequestParam(required = false) String sort,
                                @RequestParam(required = false) String direction,
                                @RequestParam(required = false) String column,
                                @RequestParam(required = false) String q) {
        return rows.page(registry.require(db), table, page, size, sort, direction, column, q);
    }

    @PostMapping("/{db}/tables/{table}/rows")
    public Map<String, String> insert(@PathVariable String db, @PathVariable String table,
                                      @RequestBody InsertRequest request) {
        return rows.insert(registry.require(db), table, request.values());
    }

    @PutMapping("/{db}/tables/{table}/rows")
    public Map<String, String> update(@PathVariable String db, @PathVariable String table,
                                      @RequestBody UpdateRequest request) {
        Target target = registry.require(db);
        return rows.update(target, table, request.key(), request.values());
    }

    /** 본문에 기본 키를 담아야 해서 DELETE 대신 POST */
    @PostMapping("/{db}/tables/{table}/rows/delete")
    public Map<String, String> delete(@PathVariable String db, @PathVariable String table,
                                      @RequestBody DeleteRequest request) {
        return rows.delete(registry.require(db), table, request.key());
    }
}
