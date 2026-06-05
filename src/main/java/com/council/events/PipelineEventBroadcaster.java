package com.council.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory event fanout for live reasoning progress.
 * <p>
 * The bounded replay buffer lets the browser connect after the run is accepted
 * without missing the first route/classification events.
 */
@Service
public class PipelineEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(PipelineEventBroadcaster.class);
    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int MAX_REPLAY_EVENTS = 80;

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Deque<PipelineEvent>> replayBuffers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String traceId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        subscribers.computeIfAbsent(traceId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(traceId, emitter));
        emitter.onTimeout(() -> remove(traceId, emitter));
        emitter.onError(error -> remove(traceId, emitter));

        replay(traceId, emitter);
        return emitter;
    }

    public PipelineEvent publish(String traceId,
                                 String phase,
                                 String status,
                                 String message,
                                 long elapsedMs,
                                 Map<String, Object> details) {
        PipelineEvent event = PipelineEvent.of(traceId, phase, status, message, elapsedMs, details);
        remember(event);
        send(traceId, event);
        if (isTerminal(event)) {
            complete(traceId);
        }
        return event;
    }

    public List<PipelineEvent> replayEvents(String traceId) {
        Deque<PipelineEvent> events = replayBuffers.get(traceId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private void remember(PipelineEvent event) {
        Deque<PipelineEvent> buffer = replayBuffers.computeIfAbsent(event.traceId(), ignored -> new ArrayDeque<>());
        synchronized (buffer) {
            buffer.addLast(event);
            while (buffer.size() > MAX_REPLAY_EVENTS) {
                buffer.removeFirst();
            }
        }
    }

    private void replay(String traceId, SseEmitter emitter) {
        for (PipelineEvent event : replayEvents(traceId)) {
            if (!sendOne(emitter, event)) {
                remove(traceId, emitter);
                return;
            }
        }
    }

    private void send(String traceId, PipelineEvent event) {
        List<SseEmitter> emitters = subscribers.getOrDefault(traceId, new CopyOnWriteArrayList<>());
        List<SseEmitter> failed = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            if (!sendOne(emitter, event)) {
                failed.add(emitter);
            }
        }
        failed.forEach(emitter -> remove(traceId, emitter));
    }

    private boolean sendOne(SseEmitter emitter, PipelineEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.phase())
                    .id(event.traceId() + ":" + event.phase() + ":" + event.elapsedMs())
                    .data(event, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("[events] Dropping stale pipeline event subscriber: {}", e.getMessage());
            return false;
        }
    }

    private void complete(String traceId) {
        List<SseEmitter> emitters = subscribers.remove(traceId);
        if (emitters == null) {
            return;
        }
        emitters.forEach(SseEmitter::complete);
    }

    private void remove(String traceId, SseEmitter emitter) {
        List<SseEmitter> emitters = subscribers.get(traceId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscribers.remove(traceId);
        }
    }

    private boolean isTerminal(PipelineEvent event) {
        return "COMPLETE".equals(event.phase()) || "ERROR".equals(event.phase());
    }
}
