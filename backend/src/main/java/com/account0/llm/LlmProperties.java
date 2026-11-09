package com.account0.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "account0.llm")
public class LlmProperties {
    private String baseUrl = "https://api.anthropic.com";
    private String apiKey;
    private String workspaceId;
    private String extractionModel = "claude-sonnet-5-5";
    private String cheapModel = "claude-haiku-5-5";
    private Map<String, Price> prices = new HashMap<>();

    public static class Price {
        private double input; private double output;
        public double getInput() { return input; } public void setInput(double v) { input = v; }
        public double getOutput() { return output; } public void setOutput(double v) { output = v; }
    }

    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    /** Cost in micro-USD. Unknown model → priced at the most expensive known rate (conservative). */
    public long costMicros(String model, long in, long out) {
        Price p = prices.get(model);
        if (p == null) p = prices.values().stream().max((a, b) -> Double.compare(a.output, b.output)).orElse(new Price());
        return Math.round(in * p.input + out * p.output); // $/1M tokens * tokens = micro-dollars
    }

    public String getBaseUrl() { return baseUrl; } public void setBaseUrl(String v) { baseUrl = v; }
    public String getApiKey() { return apiKey; } public void setApiKey(String v) { apiKey = v; }
    public String getWorkspaceId() { return workspaceId; } public void setWorkspaceId(String v) { workspaceId = v; }
    public String getExtractionModel() { return extractionModel; } public void setExtractionModel(String v) { extractionModel = v; }
    public String getCheapModel() { return cheapModel; } public void setCheapModel(String v) { cheapModel = v; }
    public Map<String, Price> getPrices() { return prices; } public void setPrices(Map<String, Price> v) { prices = v; }
}
