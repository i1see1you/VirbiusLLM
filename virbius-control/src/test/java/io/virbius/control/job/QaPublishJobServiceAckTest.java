package io.virbius.control.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaPublishJobServiceAckTest {

    private static Map<String, String> ack(String revision, String status) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("revision", revision);
        m.put("status", status);
        return m;
    }

    @Test
    void noOnlineNodesCountsAsAcked() {
        assertEquals(
                QaPublishJobService.AckDecision.ACKED,
                QaPublishJobService.evaluateAcks(Set.of(), Map.of(), 3));
    }

    @Test
    void allNodesAcked() {
        Map<String, Map<String, String>> acks = new LinkedHashMap<>();
        acks.put("n1", ack("3", "ok"));
        acks.put("n2", ack("4", "ok"));
        assertEquals(
                QaPublishJobService.AckDecision.ACKED,
                QaPublishJobService.evaluateAcks(Set.of("n1", "n2"), acks, 3));
    }

    @Test
    void missingAckIsPending() {
        Map<String, Map<String, String>> acks = new LinkedHashMap<>();
        acks.put("n1", ack("3", "ok"));
        assertEquals(
                QaPublishJobService.AckDecision.PENDING,
                QaPublishJobService.evaluateAcks(Set.of("n1", "n2"), acks, 3));
    }

    @Test
    void staleRevisionIsPending() {
        Map<String, Map<String, String>> acks = new LinkedHashMap<>();
        acks.put("n1", ack("2", "ok"));
        assertEquals(
                QaPublishJobService.AckDecision.PENDING,
                QaPublishJobService.evaluateAcks(Set.of("n1"), acks, 3));
    }

    @Test
    void failedAckFails() {
        Map<String, Map<String, String>> acks = new LinkedHashMap<>();
        acks.put("n1", ack("3", "failed"));
        assertEquals(
                QaPublishJobService.AckDecision.FAILED,
                QaPublishJobService.evaluateAcks(Set.of("n1"), acks, 3));
    }
}
