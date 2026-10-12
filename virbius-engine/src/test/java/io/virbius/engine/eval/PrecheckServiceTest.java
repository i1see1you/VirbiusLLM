package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrecheckServiceTest {

    private RuleCache cache;
    private PromptLlmClient llmClient;
    private PrecheckService service;

    @BeforeEach
    void setUp() {
        cache = mock(RuleCache.class);
        llmClient = mock(PromptLlmClient.class);
        PromptLlmProperties props =
                new PromptLlmProperties("http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true,
                        "<|im_start|>", "", null, Map.of("Violent", "prompt-violent"));
        service = new PrecheckService(cache, props, llmClient, new PromptAuditJsonParser(new ObjectMapper()));
    }

    private static RuleEntry promptRule() {
        return new RuleEntry("default", "prompt-violent", 1, "cloud", "prompt", "VIOLENT", 100, "deny",
                "full", 0, "full", "禁止暴力", null, false, null);
    }

    @Test
    void hitMapsCategoryToRule() {
        when(cache.rulesForTenant("default")).thenReturn(List.of(promptRule()));
        when(llmClient.completeDetail(anyString()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Violent\"}", null));

        PrecheckResponseDto resp = service.precheck(new PrecheckRequestDto("default", "我要打人"));

        assertTrue(resp.hit());
        assertEquals("Violent", resp.category());
        assertEquals("prompt-violent", resp.triggeredRuleId());
        assertEquals(1, resp.ruleCount());
        assertNull(resp.error());
    }

    @Test
    void missWhenSafe() {
        when(cache.rulesForTenant("default")).thenReturn(List.of(promptRule()));
        when(llmClient.completeDetail(anyString()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": \"none\"}", null));

        PrecheckResponseDto resp = service.precheck(new PrecheckRequestDto("default", "你好"));

        assertFalse(resp.hit());
        assertNull(resp.error());
    }

    @Test
    void errorIsSurfacedForPublishChainFailClosed() {
        when(cache.rulesForTenant("default")).thenReturn(List.of(promptRule()));
        when(llmClient.completeDetail(anyString()))
                .thenReturn(new PromptLlmClient.CompleteResult("", "connection refused"));

        PrecheckResponseDto resp = service.precheck(new PrecheckRequestDto("default", "内容"));

        assertFalse(resp.hit());
        assertEquals("connection refused", resp.error());
    }

    @Test
    void noPromptRulesShortCircuitsWithoutLlmCall() {
        when(cache.rulesForTenant("default")).thenReturn(List.of());

        PrecheckResponseDto resp = service.precheck(new PrecheckRequestDto("default", "内容"));

        assertFalse(resp.hit());
        assertEquals(0, resp.ruleCount());
        assertNull(resp.error());
        verify(llmClient, never()).completeDetail(anyString());
    }

    @Test
    void blankContentRejected() {
        PrecheckResponseDto resp = service.precheck(new PrecheckRequestDto("default", " "));

        assertFalse(resp.hit());
        assertEquals("content required", resp.error());
        verify(llmClient, never()).completeDetail(anyString());
    }
}
