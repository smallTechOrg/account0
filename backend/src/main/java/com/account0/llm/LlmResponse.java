package com.account0.llm;

public record LlmResponse(String text, long inputTokens, long outputTokens, String stopReason) {}
