package io.virbius.control.service;

import io.virbius.control.domain.QaEntry;
import io.virbius.control.domain.dto.request.QaEntryInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class QaEntryValidator {

    static final int MAX_ENTRIES = 10000;
    static final int MAX_REGEX_ENTRIES = 200;
    static final int MAX_ANSWER_LEN = 4096;
    static final int MAX_QUESTION_LEN = 512;
    static final int MAX_VARIANTS = 20;

    private QaEntryValidator() {}

    public static List<QaEntry> validateAndNormalize(
            String tenantId, String qaId, List<QaEntryInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("entries required");
        }
        if (inputs.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("entries exceed limit " + MAX_ENTRIES);
        }
        List<QaEntry> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int regexCount = 0;
        for (QaEntryInput in : inputs) {
            String mode = in.match_mode() == null || in.match_mode().isBlank()
                    ? "exact"
                    : in.match_mode().trim().toLowerCase();
            if (!mode.equals("exact") && !mode.equals("contains") && !mode.equals("regex")) {
                throw new IllegalArgumentException(
                        "unsupported match_mode: " + in.match_mode() + " (exact|contains|regex)");
            }
            String answer = in.answer();
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("entry answer required");
            }
            if (answer.length() > MAX_ANSWER_LEN) {
                throw new IllegalArgumentException("answer exceeds " + MAX_ANSWER_LEN + " chars");
            }
            String question = in.question() == null ? "" : in.question().trim();
            if (question.length() > MAX_QUESTION_LEN) {
                throw new IllegalArgumentException("question exceeds " + MAX_QUESTION_LEN + " chars");
            }
            List<String> variants = in.variants() == null ? List.of() : in.variants();
            if (variants.size() > MAX_VARIANTS) {
                throw new IllegalArgumentException("variants exceed " + MAX_VARIANTS);
            }
            List<String> patterns = new ArrayList<>();
            if (!question.isBlank()) {
                patterns.add(question);
            }
            for (String v : variants) {
                if (v != null && v.length() > MAX_QUESTION_LEN) {
                    throw new IllegalArgumentException("variant exceeds " + MAX_QUESTION_LEN + " chars");
                }
                if (v != null && !v.isBlank()) {
                    patterns.add(v.trim());
                }
            }
            if (patterns.isEmpty()) {
                throw new IllegalArgumentException("entry question or variant required");
            }
            if (mode.equals("regex")) {
                regexCount++;
                for (String p : patterns) {
                    validateRegex(p);
                }
            }
            String entryId = in.entry_id() == null || in.entry_id().isBlank()
                    ? UUID.randomUUID().toString()
                    : in.entry_id().trim();
            if (!ids.add(entryId)) {
                throw new IllegalArgumentException("duplicate entry_id: " + entryId);
            }
            out.add(new QaEntry(
                    tenantId,
                    qaId,
                    entryId,
                    question,
                    variants,
                    answer,
                    mode,
                    in.tags() == null ? List.of() : in.tags(),
                    in.priority() == null ? 0 : in.priority(),
                    in.enabled() == null || in.enabled(),
                    parseInstant(in.expires_at()),
                    null,
                    null,
                    null));
        }
        if (regexCount > MAX_REGEX_ENTRIES) {
            throw new IllegalArgumentException("regex entries exceed limit " + MAX_REGEX_ENTRIES);
        }
        return out;
    }

    static void validateRegex(String pattern) {
        if (looksLikeUnsupportedRegex(pattern)) {
            throw new IllegalArgumentException(
                    "regex uses unsupported construct (lookaround/backreference): " + pattern);
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex \"" + pattern + "\": " + e.getDescription());
        }
    }

    static boolean looksLikeUnsupportedRegex(String pattern) {
        if (pattern.contains("(?=")
                || pattern.contains("(?!")
                || pattern.contains("(?<=")
                || pattern.contains("(?<!")) {
            return true;
        }
        return pattern.matches(".*\\\\[1-9].*");
    }

    static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Instant.parse(raw.trim());
    }
}
