package com.meetingroom.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds the UI's SSE connections and publishes each {@link PipelineEvent} as
 * {@code event: pipeline}. No history is kept: the client gets the full trace in the chat response.
 */
@Component
public class PipelineBroadcaster {

	public static final String EVENT_PIPELINE = "pipeline";

	private static final long CONNECTION_TIMEOUT_MS = 30 * 60 * 1000L;

	private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

	public SseEmitter subscribe(long traceId) {
		SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MS);
		List<SseEmitter> list = emitters.computeIfAbsent(traceId, key -> new CopyOnWriteArrayList<>());
		list.add(emitter);
		Runnable cleanup = () -> {
			List<SseEmitter> remaining = emitters.get(traceId);
			if (remaining != null) {
				remaining.remove(emitter);
				if (remaining.isEmpty()) {
					emitters.remove(traceId);
				}
			}
		};
		emitter.onCompletion(cleanup);
		emitter.onTimeout(cleanup);
		emitter.onError(error -> cleanup.run());
		try {
			emitter.send(SseEmitter.event().name("ready")
					.data(Map.of("traceId", traceId, "startedAt", System.currentTimeMillis())));
		} catch (IOException ex) {
			cleanup.run();
		}
		return emitter;
	}

	public void publish(PipelineEvent event) {
		publish(event.traceId(), EVENT_PIPELINE, event.toMap());
	}

	public void publishAll(PipelineEvent[] events) {
		for (PipelineEvent event : events) {
			publish(event);
		}
	}

	/** event: "ready" when the stream opens, "pipeline" per step, "error"/"trace" at the end. */
	public void publish(long traceId, String eventName, Map<String, Object> data) {
		List<SseEmitter> targets = emitters.get(traceId);
		if (targets == null) {
			return;
		}
		for (SseEmitter emitter : targets) {
			try {
				emitter.send(SseEmitter.event().name(eventName).data(data));
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
