package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.dto.request.QaEntryInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QaEntryValidatorTest {

    private static QaEntryInput input(String question, String answer, String mode) {
        return new QaEntryInput(null, question, null, answer, mode, null, null, null, null);
    }

    @Test
    void normalizesAndDefaults() {
        List<QaEntry> out = QaEntryValidator.validateAndNormalize(
                "default", "qa1", List.of(input("什么是核心价值观", "标准回答", null)));
        assertEquals(1, out.size());
        assertEquals("exact", out.get(0).matchMode());
        assertTrue(out.get(0).enabled());
        assertEquals("default", out.get(0).tenantId());
        assertEquals("qa1", out.get(0).qaId());
    }

    @Test
    void rejectsBlankAnswer() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QaEntryValidator.validateAndNormalize(
                        "default", "qa1", List.of(input("q", "  ", "exact"))));
    }

    @Test
    void rejectsOversizedAnswer() {
        String big = "a".repeat(QaEntryValidator.MAX_ANSWER_LEN + 1);
        assertThrows(
                IllegalArgumentException.class,
                () -> QaEntryValidator.validateAndNormalize(
                        "default", "qa1", List.of(input("q", big, "exact"))));
    }

    @Test
    void rejectsUnsupportedMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QaEntryValidator.validateAndNormalize(
                        "default", "qa1", List.of(input("q", "a", "similar"))));
    }

    @Test
    void rejectsLookaroundRegex() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QaEntryValidator.validateAndNormalize(
                        "default", "qa1", List.of(input("(?=abc)", "a", "regex"))));
    }

    @Test
    void rejectsBackreferenceRegex() {
        assertTrue(QaEntryValidator.looksLikeUnsupportedRegex("(a)\\1"));
    }

    @Test
    void acceptsValidRegex() {
        List<QaEntry> out = QaEntryValidator.validateAndNormalize(
                "default", "qa1", List.of(input("台.*湾", "a", "regex")));
        assertEquals("regex", out.get(0).matchMode());
    }

    @Test
    void rejectsMissingQuestionAndVariants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QaEntryValidator.validateAndNormalize(
                        "default", "qa1", List.of(input("  ", "a", "exact"))));
    }

    @Test
    void rejectsDuplicateEntryIds() {
        QaEntryInput a = new QaEntryInput("dup", "q1", null, "a", "exact", null, null, null, null);
        QaEntryInput b = new QaEntryInput("dup", "q2", null, "a", "exact", null, null, null, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> QaEntryValidator.validateAndNormalize("default", "qa1", List.of(a, b)));
    }

    @Test
    void rejectsTooManyEntries() {
        List<QaEntryInput> inputs = new ArrayList<>();
        for (int i = 0; i <= QaEntryValidator.MAX_ENTRIES; i++) {
            inputs.add(input("q" + i, "a", "exact"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> QaEntryValidator.validateAndNormalize("default", "qa1", inputs));
    }
}
