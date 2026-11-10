package com.account0.llm;

import com.account0.common.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm")
public class LlmController {
    private final LlmGateway gw;
    private final LlmProperties props;
    private final SettingsService settings;
    public LlmController(LlmGateway gw, LlmProperties props, SettingsService settings) { this.gw = gw; this.props = props; this.settings = settings; }

    @GetMapping("/usage")
    public Map<String, Object> usage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currentMonth", YearMonth.now(ZoneOffset.UTC).toString());
        m.put("currentMonthCostUsd", gw.monthSpendMicros(YearMonth.now(ZoneOffset.UTC)) / 1e6);
        m.put("monthlyCapUsd", Double.parseDouble(settings.get("llm.monthly_cap_usd", "20")));
        m.put("months", gw.monthlySummary());
        m.put("recent", gw.recent(50));
        return m;
    }

    /** Real, cheap round-trip (haiku) to prove key + workspace header work. Costs a fraction of a cent. */
    @PostMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", props.getCheapModel());
        try {
            LlmResponse r = gw.call(new LlmRequest(props.getCheapModel(), "key-check", null, null,
                    List.of(Map.of("type", "text", "text", "Reply with exactly: OK")), 16));
            m.put("ok", true); m.put("reply", r.text().trim()); m.put("inputTokens", r.inputTokens()); m.put("outputTokens", r.outputTokens());
        } catch (LlmException e) {
            m.put("ok", false); m.put("error", e.getMessage());
        }
        return m;
    }
}
