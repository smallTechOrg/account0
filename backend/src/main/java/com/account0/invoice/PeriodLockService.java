package com.account0.invoice;

import com.account0.common.ApiException;
import com.account0.common.Tenant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PeriodLockService {
    private final JdbcTemplate jdbc;
    public PeriodLockService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean isLocked(String period) {
        if (period == null) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM filing WHERE tenant_id=? AND period=? AND locked=1", Integer.class, Tenant.current(), period);
        return n != null && n > 0;
    }
    public void assertOpen(String period) {
        if (isLocked(period)) throw new ApiException(HttpStatus.CONFLICT, "Period " + period + " is locked (a return has been filed). Unlock it in Filing history first — the unlock is audited.");
    }
}
