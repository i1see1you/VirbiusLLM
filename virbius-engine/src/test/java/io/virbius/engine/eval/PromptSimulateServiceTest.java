package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptLlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptSimulateServiceTest {

    private PromptLlmClient llmClient;
    private PromptSimulateService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(PromptLlmClient.class);
        PromptLlmProperties props =
                new PromptLlmProperties("http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true, "<|im_start|>", "");
        service = new PromptSimulateService(props, llmClient, new PromptAuditJsonParser(new ObjectMapper()));
    }

    @Test
    void hitWhenTriggeredIdMatchesDraftRule() {
        when(llmClient.complete(anyString()))
                .thenReturn("{\"hit_rule\": true, \"triggered_id\": \"Rule_201\", \"reason\": \"arch\"}");

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "Rule_201", "禁止讨论架构", "ARCH", "com.baidu.internal auth"));

        assertTrue(resp.hit());
        assertTrue(resp.llmHitRule());
        assertEquals("Rule_201", resp.triggeredId());
        assertEquals("{\"hit_rule\": true, \"triggered_id\": \"Rule_201\", \"reason\": \"arch\"}", resp.llmResponse());
        verify(llmClient).complete(org.mockito.ArgumentMatchers.argThat(p -> p.contains("[Rule_201]")
                && p.contains("禁止讨论架构")
                && !p.contains("[Rule_202]")));
    }

    @Test
    void missWhenTriggeredIdDiffersFromDraftRule() {
        when(llmClient.complete(anyString()))
                .thenReturn("{\"hit_rule\": true, \"triggered_id\": \"Rule_999\", \"reason\": \"other\"}");

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "Rule_201", "body", "ARCH", "hello"));

        assertFalse(resp.hit());
        assertTrue(resp.llmHitRule());
        assertEquals("Rule_999", resp.triggeredId());
    }

    @Test
    void missWhenLlmSaysNoHit() {
        when(llmClient.complete(anyString()))
                .thenReturn("{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"\"}");

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "Rule_201", "body", "ARCH", "hello"));

        assertFalse(resp.hit());
        assertFalse(resp.llmHitRule());
    }

    @Test
    void errorWhenLlmEmpty() {
        when(llmClient.complete(anyString())).thenReturn("");

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "Rule_201", "body", "ARCH", "hello"));

        assertFalse(resp.hit());
        assertTrue(resp.error().startsWith("LLM empty response"));
        assertEquals("", resp.llmResponse());
    }
}
