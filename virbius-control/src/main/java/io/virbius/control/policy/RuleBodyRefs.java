package io.virbius.control.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.policy.ValueSource;

public record RuleBodyRefs(String listName, String cumulativeName, ValueSource valueSource) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static RuleBodyRefs parse(Object body) {
        if (body == null) {
            return new RuleBodyRefs(null, null, null);
        }
        try {
            JsonNode node;
            if (body instanceof String s) {
                node = MAPPER.readTree(s);
            } else {
                node = MAPPER.valueToTree(body);
            }
            String listName = text(node, "list_name");
            String cumulativeName = text(node, "cumulative_name");
            ValueSource vs = ValueSource.fromJson(node.get("value_source"));
            return new RuleBodyRefs(listName, cumulativeName, vs);
        } catch (Exception e) {
            return new RuleBodyRefs(null, null, null);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }
}
