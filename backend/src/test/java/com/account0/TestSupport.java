package com.account0;

import com.account0.llm.LlmRequest;
import com.account0.llm.LlmResponse;
import com.account0.llm.LlmTransport;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class TestSupport {
    private TestSupport() {}

    public static java.util.Map<String, Object> map(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static void freshDb(DynamicPropertyRegistry r) {
        try {
            Path dir = Files.createTempDirectory("account0-test");
            r.add("account0.data-dir", dir::toString);
            r.add("account0.inbox-dir", () -> dir.resolve("inbox").toString());
            r.add("spring.datasource.url", () -> "jdbc:sqlite:" + dir.resolve("t.db") + "?foreign_keys=on");
            r.add("account0.llm.api-key", () -> "test-key");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Fake wire: tests set {@link #responder}; every request is captured. */
    public static class FakeTransport implements LlmTransport {
        public final List<LlmRequest> requests = new ArrayList<>();
        public Function<LlmRequest, LlmResponse> responder = r -> new LlmResponse("OK", 10, 2, "end_turn");
        @Override public synchronized LlmResponse send(LlmRequest req) { requests.add(req); return responder.apply(req); }
    }

    @TestConfiguration
    public static class FakeLlmConfig {
        @Bean @Primary public FakeTransport fakeTransport() { return new FakeTransport(); }
    }
}
