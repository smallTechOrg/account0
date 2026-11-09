package com.account0.llm;

/** The wire. Real implementation = {@link AnthropicHttpTransport}; tests substitute a fake. */
public interface LlmTransport {
    LlmResponse send(LlmRequest req) throws LlmException;
}
