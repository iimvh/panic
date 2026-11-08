package com.panic.pipeline;

import com.panic.pipeline.agui.AgUiStreamListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Publishes each {@link PipelineEvent} and answer chunk to the {@link AgUiStreamListener}s
 * registered for its trace. The chat endpoint registers its own listener so steps and answer
 * deltas stream in realtime on the same response. No history is kept: the recorder's trace
 * does not survive in here.
 */
@Component
public class PipelineBroadcaster {

	private final Map<Long, List<AgUiStreamListener>> listeners = new ConcurrentHashMap<>();

	/** Registers the realtime listener of the chat stream for a trace. */
	public void register(long traceId, AgUiStreamListener stream) {
		listeners.computeIfAbsent(traceId, key -> new CopyOnWriteArrayList<>()).add(stream);
	}

	/** Removes the listener registry of a trace (its stream is closing). */
	public void unregister(long traceId) {
		listeners.remove(traceId);
	}

	/** Publishes one recorded pipeline step to every listener registered for its trace. */
	public void publish(PipelineEvent event) {
		publish(event.traceId(), event);
	}

	/** Publishes one answer chunk to every listener registered for the trace. */
	public void publishToken(long traceId, String text, String agentId) {
		if (text == null || text.isEmpty()) {
			return;
		}
		List<AgUiStreamListener> targets = listeners.get(traceId);
		if (targets == null) {
			return;
		}
		for (AgUiStreamListener stream : targets) {
			stream.answerDelta(text, agentId);
		}
	}

	private void publish(long traceId, PipelineEvent event) {
		List<AgUiStreamListener> targets = listeners.get(traceId);
		if (targets == null) {
			return;
		}
		for (AgUiStreamListener stream : targets) {
			try {
				stream.pipelineStep(event);
			} catch (RuntimeException ex) {
				// One broken listener must not break the recorder's own emitting thread;
				// the stream reports its send failure to the servlet container directly.
			}
		}
	}
}
