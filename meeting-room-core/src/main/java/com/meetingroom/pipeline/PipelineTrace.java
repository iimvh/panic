package com.meetingroom.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects the events of one HTTP request so every SSE subscriber renders the same session.
 * <p>
 * Spring MVC (servlet) runs LLM and tool calls synchronously on one thread, so a
 * {@link ThreadLocal} is enough to find the current trace without changing signatures.
 */
public final class PipelineTrace {

	public static final class Builder {

		private final long traceId;
		private final AtomicLong counter = new AtomicLong();
		private final Deque<PipelineEvent> events = new ArrayDeque<>();

		Builder(long traceId) {
			this.traceId = traceId;
		}

		public PipelineEvent next(String phase, String actor, String title, String detail) {
			return next(phase, PipelineEvent.STATUS_RUNNING, actor, title, detail, null);
		}

		public PipelineEvent next(String phase, String status, String actor, String title, String detail) {
			return next(phase, status, actor, title, detail, null);
		}

		public PipelineEvent next(String phase, String status, String actor, String title, String detail, Long parentId) {
			return next(phase, status, actor, title, detail, parentId, null, 0);
		}

		public PipelineEvent next(String phase, String status, String actor, String title, String detail, Long parentId,
				String agentId, int depth) {
			return append(phase, status, actor, title, detail, System.currentTimeMillis(), null, parentId, agentId,
					depth);
		}

		/** A step that completes immediately (e.g. JSON parsing) with a measured duration. */
		public PipelineEvent done(String phase, String status, String actor, String title, String detail,
				long durationMs) {
			return done(phase, status, actor, title, detail, durationMs, null, 0);
		}

		public PipelineEvent done(String phase, String status, String actor, String title, String detail,
				long durationMs, String agentId, int depth) {
			return append(phase, status, actor, title, detail, System.currentTimeMillis() - Math.max(0, durationMs),
					durationMs, null, agentId, depth);
		}

		public synchronized PipelineEvent close(PipelineEvent event, String status, String title, String detail,
				long durationMs, Long parentId) {
			PipelineEvent updated = new PipelineEvent(event.id(), event.traceId(), event.phase(), event.actor(), status,
					title, detail, event.startedAt(), durationMs, parentId,
					event.agentId(), event.depth() == null ? 0 : event.depth());
			PipelineEvent[] snapshot = events.toArray(PipelineEvent[]::new);
			for (int i = 0; i < snapshot.length; i++) {
				if (snapshot[i].id() == event.id()) {
					PipelineEvent[] replaced = snapshot.clone();
					replaced[i] = updated;
					events.clear();
					for (PipelineEvent item : replaced) {
						events.add(item);
					}
					return updated;
				}
			}
			return updated;
		}

		private synchronized PipelineEvent append(String phase, String status, String actor, String title,
				String detail, long startedAt, Long durationMs, Long parentId, String agentId, int depth) {
			PipelineEvent event = new PipelineEvent(counter.incrementAndGet(), traceId, phase, actor, status, title,
					detail, startedAt, durationMs, parentId, agentId, depth);
			events.add(event);
			return event;
		}
	}

	private static final ThreadLocal<Builder> CURRENT = new ThreadLocal<>();

	private PipelineTrace() {
	}

	public static Builder begin(long traceId) {
		Builder builder = new Builder(traceId);
		CURRENT.set(builder);
		return builder;
	}

	public static Builder current() {
		return CURRENT.get();
	}

	public static PipelineEvent[] end() {
		Builder builder = CURRENT.get();
		CURRENT.remove();
		return builder == null ? new PipelineEvent[0] : builder.events.toArray(PipelineEvent[]::new);
	}

	public static void clear() {
		CURRENT.remove();
	}
}
