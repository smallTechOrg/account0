package com.account0.llm;

import com.account0.common.SettingsService;
import com.account0.common.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Every LLM call goes through here: spend-cap check, call, usage+cost ledger row (success or failure).
 */
@Service
public class LlmGateway {
    private final LlmTransport transport;
    private final LlmProperties props;
    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public LlmGateway(LlmTransport transport, LlmProperties props, JdbcTemplate jdbc, SettingsService settings) {
        this.transport = transport; this.props = props; this.jdbc = jdbc; this.settings = settings;
    }

    public LlmResponse call(LlmRequest req) throws LlmException {
        long capMicros = Math.round(Double.parseDouble(settings.get("llm.monthly_cap_usd", "20")) * 1_000_000);
        long spent = monthSpendMicros(YearMonth.now(ZoneOffset.UTC));
        if (spent >= capMicros) {
            log(req, 0, 0, 0, "BLOCKED_CAP", "monthly spend cap reached", 0);
            throw new LlmException(String.format("Monthly LLM spend cap reached ($%.2f of $%.2f). Raise it in Settings.", spent / 1e6, capMicros / 1e6));
        }
        long t0 = System.currentTimeMillis();
        try {
            LlmResponse r = transport.send(req);
            log(req, r.inputTokens(), r.outputTokens(), props.costMicros(req.model(), r.inputTokens(), r.outputTokens()), "OK", null, System.currentTimeMillis() - t0);
            return r;
        } catch (LlmException e) {
            log(req, 0, 0, 0, "ERROR", e.getMessage(), System.currentTimeMillis() - t0);
            throw e;
        }
    }

    private void log(LlmRequest req, long in, long out, long cost, String status, String err, long ms) {
        jdbc.update("INSERT INTO llm_call(tenant_id, model, purpose, ref, input_tokens, output_tokens, cost_usd_micros, status, error, duration_ms) VALUES (?,?,?,?,?,?,?,?,?,?)",
                Tenant.current(), req.model(), req.purpose(), req.ref(), in, out, cost, status, err, ms);
    }

    public long monthSpendMicros(YearMonth ym) {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(cost_usd_micros),0) FROM llm_call WHERE tenant_id=? AND substr(created_at,1,7)=?",
                Long.class, Tenant.current(), ym.toString());
        return v == null ? 0 : v;
    }

    public List<Map<String, Object>> monthlySummary() {
        return jdbc.queryForList("""
                SELECT substr(created_at,1,7) AS month, COUNT(*) AS calls, SUM(input_tokens) AS input_tokens,
                       SUM(output_tokens) AS output_tokens, SUM(cost_usd_micros)/1000000.0 AS cost_usd
                FROM llm_call WHERE tenant_id=? GROUP BY 1 ORDER BY 1 DESC""", Tenant.current());
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("SELECT id, created_at, model, purpose, ref, input_tokens, output_tokens, cost_usd_micros/1000000.0 AS cost_usd, status, error, duration_ms FROM llm_call WHERE tenant_id=? ORDER BY id DESC LIMIT ?",
                Tenant.current(), limit);
    }
}
