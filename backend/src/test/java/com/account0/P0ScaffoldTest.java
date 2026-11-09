package com.account0;

import com.account0.llm.LlmException;
import com.account0.llm.LlmGateway;
import com.account0.llm.LlmRequest;
import com.account0.common.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.FakeLlmConfig.class)
class P0ScaffoldTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { TestSupport.freshDb(r); }
    @Autowired TestRestTemplate rest;
    @Autowired LlmGateway gw;
    @Autowired JdbcTemplate jdbc;
    @Autowired SettingsService settings;
    @Autowired TestSupport.FakeTransport fake;

    @Test void healthIsUpAndMigrated() {
        Map<?, ?> h = rest.getForObject("/health", Map.class);
        assertThat(h.get("status")).isEqualTo("UP");
        assertThat(h.get("llmKeyConfigured")).isEqualTo(true);
        assertThat(Integer.parseInt(String.valueOf(h.get("schemaVersion")))).isGreaterThanOrEqualTo(1);
    }

    @Test void gatewayRecordsCostAndEnforcesCap() throws Exception {
        jdbc.update("DELETE FROM llm_call");
        settings.set("llm.monthly_cap_usd", "1.00");
        fake.responder = r -> new com.account0.llm.LlmResponse("hi", 1_000_000, 0, "end_turn"); // 1M input tokens on sonnet = $2
        gw.call(new LlmRequest("claude-sonnet-5-5", "test", null, null, List.of(Map.of("type", "text", "text", "x")), 10));
        Long micros = jdbc.queryForObject("SELECT SUM(cost_usd_micros) FROM llm_call WHERE status='OK'", Long.class);
        assertThat(micros).isEqualTo(2_000_000L);
        assertThatThrownBy(() -> gw.call(new LlmRequest("claude-sonnet-5-5", "test", null, null, List.of(), 10)))
                .isInstanceOf(LlmException.class).hasMessageContaining("cap");
        settings.set("llm.monthly_cap_usd", "20.00");
    }
}
