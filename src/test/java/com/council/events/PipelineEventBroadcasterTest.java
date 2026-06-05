package com.council.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineEventBroadcasterTest {

    @Test
    @DisplayName("Replay buffer preserves recent events for late subscribers")
    void replayBufferPreservesRecentEvents() {
        PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();

        broadcaster.publish("trace-1", "START", "running", "started", 0, Map.of());
        broadcaster.publish("trace-1", "CLASSIFY", "done", "classified", 12,
                Map.of("taskType", "GENERAL_REASONING"));

        var events = broadcaster.replayEvents("trace-1");

        assertEquals(2, events.size());
        assertEquals("START", events.getFirst().phase());
        assertEquals("CLASSIFY", events.getLast().phase());
        assertEquals("GENERAL_REASONING", events.getLast().details().get("taskType"));
    }

    @Test
    @DisplayName("Replay buffer is bounded to protect heap during long runs")
    void replayBufferIsBounded() {
        PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();

        for (int i = 0; i < 100; i++) {
            broadcaster.publish("trace-2", "DRAFT", "running", "event " + i, i, Map.of("index", i));
        }

        var events = broadcaster.replayEvents("trace-2");

        assertEquals(80, events.size());
        assertTrue((Integer) events.getFirst().details().get("index") >= 20);
        assertEquals(99, events.getLast().details().get("index"));
    }
}
