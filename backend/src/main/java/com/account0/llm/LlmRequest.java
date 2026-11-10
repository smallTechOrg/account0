package com.account0.llm;

import java.util.List;
import java.util.Map;

/**
 * One Messages API call. {@code content} is the user-turn content blocks (text / document / image) in wire format.
 */
public record LlmRequest(String model, String purpose, String ref, String system, List<Map<String, Object>> content, int maxTokens) {}
