package io.virbius.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.prompt-llm")
public record PromptLlmProperties(
        String baseUrl,
        String model,
        String apiPath,
        int timeoutMs,
        boolean failOpen,
        String imStart,
        String imEnd) {

    public PromptLlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:11434";
        }
        if (model == null || model.isBlank()) {
            model = "virbius-prompt-1b";
        }
        if (apiPath == null || apiPath.isBlank()) {
            apiPath = "/v1/chat/completions";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 3000;
        }
        if (imStart == null || imStart.isBlank()) {
            imStart = "<|im_start|>";
        }
        if (imEnd == null) {
            imEnd = "";
        }
    }
}
