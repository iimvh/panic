package com.panic.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Publishes each {@link PipelineEvent} to the emitters registered for its trace. The chat endpoint
 * registers its own {@link SseEmitter} so pipeline frames stream in realtime on the same response.
 * No history is kept: the final chat result still carries the full trace.
 */
@Component
public class PipelineBroadcaster {

	public static final String EVENT_PIPELINE = "pipeline";

	private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

	/** Registers a raw emitter (e.g. the chat stream) to receive `pipeline` frames for a trace. */
	public void register(long traceId, SseEmitter emitter) {
		emitters.computeIfAbsent(traceId, key -> new CopyOnWriteArrayList<>()).add(emitter);
	}

	public void unregister(long traceId) {
		emitters.remove(traceId);
	}

	public void publish(PipelineEvent event) {
		publish(event.traceId(), EVENT_PIPELINE, event.toMap());
	}

	public void publishAll(PipelineEvent[] events) {
		for (PipelineEvent event : events) {
			publish(event);
		}
	}

	/** Sends one event to every emitter registered for the trace; unregistered traces are ignored. */
	public void publish(long traceId, String eventName, Map<String, Object> data) {
		List<SseEmitter> targets = emitters.get(traceId);
		if (targets == null) {
			return;
		}
		for (SseEmitter emitter : targets) {
			try {
				emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
			} catch (IOException | IllegalStateException ex) {
				emitter.completeWithError(ex);
			}
		}
	}

	public void complete(long traceId) {
		List<SseEmitter> list = emitters.remove(traceId);
		if (list != null) {
			list.forEach(SseEmitter::complete);
		}
	}
}
