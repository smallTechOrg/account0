package com.account0.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * STUB: when env {@code ACCOUNT0_LLM_STUB_FIXTURE} points at a file, every LLM call returns
 * that file's text instead of calling Claude. Used only by the browser-level UI gate so no tokens are spent.
 */
@Component
@Primary
@ConditionalOnExpression("!'${ACCOUNT0_LLM_STUB_FIXTURE:}'.isEmpty()")
public class StubFixtureTransport implements LlmTransport {
    private final String text;
    public StubFixtureTransport(@Value("${ACCOUNT0_LLM_STUB_FIXTURE}") String path) {
        try { this.text = Files.readString(Path.of(path)); } catch (Exception e) { throw new IllegalStateException("cannot read stub fixture " + path, e); }
    }
    @Override public LlmResponse send(LlmRequest req) { return new LlmResponse(text, 0, 0, "end_turn"); }
}
