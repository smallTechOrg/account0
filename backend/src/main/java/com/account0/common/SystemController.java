package com.account0.common;

import com.account0.llm.LlmProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SystemController {
    private final JdbcTemplate jdbc;
    private final LlmProperties llm;
    private final SettingsService settings;
    private final AuditService audit;

    public SystemController(JdbcTemplate jdbc, LlmProperties llm, SettingsService settings, AuditService audit) {
        this.jdbc = jdbc; this.llm = llm; this.settings = settings; this.audit = audit;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        String schema = jdbc.queryForObject("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success=1", String.class);
        m.put("status", one != null && one == 1 ? "UP" : "DOWN");
        m.put("db", "sqlite");
        m.put("schemaVersion", schema);
        m.put("llmKeyConfigured", llm.isConfigured());
        m.put("llmWorkspaceConfigured", llm.getWorkspaceId() != null && !llm.getWorkspaceId().isBlank());
        return m;
    }

    @GetMapping("/api/settings")
    public Map<String, String> settings() { return settings.all(); }

    @PutMapping("/api/settings/{key}")
    public Map<String, String> put(@PathVariable String key, @RequestBody Map<String, String> body) {
        if (!key.matches("[a-z0-9_.]+")) throw ApiException.bad("bad key");
        settings.set(key, body.get("value"));
        return settings.all();
    }

    @GetMapping("/api/audit")
    public List<Map<String, Object>> audit(@RequestParam(required = false) String entity, @RequestParam(required = false) String entityId,
                                           @RequestParam(defaultValue = "200") int limit) {
        return audit.list(entity, entityId, Math.min(limit, 1000));
    }
}
