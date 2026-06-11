package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptMatrixBuilderTest {

    @Test
    void buildsMatrixWithRuleIds() {
        RuleEntry r1 = new RuleEntry(
                "default",
                "Rule_201",
                1,
                "cloud",
                "prompt",
                "RULE_201",
                100,
                "deny",
                "dry_run",
                100,
                "dry_run",
                "检查用户是否在诱导大模型编写针对企业内部特定前缀的敏感核心架构逻辑。",
                null,
                false,
                null);
        PromptLlmProperties props = new PromptLlmProperties(
                "http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true, "<|im_start|>", "");
        String prompt = PromptMatrixBuilder.buildChatMlPrompt(props, List.of(r1), "hello");
        assertTrue(prompt.contains("[Rule_201]"));
        assertTrue(prompt.contains("【User Prompt】: \"hello\""));
        assertTrue(prompt.contains("hit_rule"));
    }

    @Test
    void parsesAuditJson() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse(
                """
                {
                  "hit_rule": true,
                  "triggered_id": "Rule_201",
                  "reason": "涉及 com.baidu 架构"
                }
                """);
        assertTrue(r.hitRule());
        assertEquals("Rule_201", r.triggeredId());
    }

    @Test
    void parsesJsonEmbeddedInText() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("说明如下：{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"\"}");
        assertFalse(r.hitRule());
    }
}
