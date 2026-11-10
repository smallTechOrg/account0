package com.account0.company;

import com.account0.common.ApiException;
import com.account0.common.AuditService;
import com.account0.common.Tenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class CompanyService {
    static final List<String> FIELDS = List.of("legal_name", "trade_name", "cin", "gstin", "pan", "tan", "state_code", "address",
            "fy_start_month", "gst_filing_frequency", "lut_arn", "lut_valid_from", "lut_valid_to");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public CompanyService(JdbcTemplate jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    public Map<String, Object> get() {
        return jdbc.queryForMap("SELECT * FROM company_profile WHERE tenant_id=?", Tenant.current());
    }

    public String gstin() { Object g = get().get("gstin"); return g == null ? null : g.toString(); }

    public Map<String, Object> update(Map<String, Object> in) {
        Map<String, Object> before = get();
        Map<String, Object> v = new LinkedHashMap<>();
        for (String f : FIELDS) if (in.containsKey(f)) {
            Object x = in.get(f);
            if (x instanceof String s) { s = s.trim(); x = s.isEmpty() ? null : s; }
            v.put(f, x);
        }
        List<String> errors = validate(v);
        if (!errors.isEmpty()) throw ApiException.bad(String.join("; ", errors));
        if (v.get("gstin") != null) {
            String g = v.get("gstin").toString();
            v.put("state_code", Gstin.stateCode(g));
            v.putIfAbsent("pan", Gstin.pan(g));
            if (v.get("pan") == null) v.put("pan", Gstin.pan(g));
        }
        if (v.isEmpty()) return before;
        StringBuilder sql = new StringBuilder("UPDATE company_profile SET ");
        List<Object> args = new ArrayList<>();
        for (var e : v.entrySet()) { sql.append(e.getKey()).append("=?, "); args.add(e.getValue()); }
        sql.append("updated_at=? WHERE tenant_id=?");
        args.add(Instant.now().toString()); args.add(Tenant.current());
        jdbc.update(sql.toString(), args.toArray());
        audit.diff("user", "company_profile", Tenant.current(), before, v);
        return get();
    }

    static List<String> validate(Map<String, Object> v) {
        List<String> e = new ArrayList<>();
        upper(v, "gstin"); upper(v, "pan"); upper(v, "tan"); upper(v, "cin");
        if (v.get("gstin") != null && !Gstin.isValid((String) v.get("gstin"))) e.add("GSTIN is invalid (format or check digit)");
        if (v.get("pan") != null && !((String) v.get("pan")).matches("[A-Z]{5}[0-9]{4}[A-Z]")) e.add("PAN format invalid");
        if (v.get("pan") != null && v.get("gstin") != null && Gstin.isValid((String) v.get("gstin")) && !v.get("pan").equals(Gstin.pan((String) v.get("gstin"))))
            e.add("PAN does not match GSTIN characters 3-12");
        if (v.get("tan") != null && !((String) v.get("tan")).matches("[A-Z]{4}[0-9]{5}[A-Z]")) e.add("TAN format invalid");
        if (v.get("cin") != null && !((String) v.get("cin")).matches("[LU][0-9]{5}[A-Z]{2}[0-9]{4}[A-Z]{3}[0-9]{6}")) e.add("CIN format invalid");
        if (v.get("gst_filing_frequency") != null && !List.of("MONTHLY", "QUARTERLY").contains(v.get("gst_filing_frequency"))) e.add("filing frequency must be MONTHLY or QUARTERLY");
        return e;
    }
    private static void upper(Map<String, Object> v, String k) { if (v.get(k) instanceof String s) v.put(k, s.toUpperCase()); }
}
