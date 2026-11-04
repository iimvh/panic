package com.panic.pipeline;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects the events of one HTTP request so every subscriber renders the same session.
 * <p>
 * A {@link Builder} belongs to one request trace and is safe to share across threads
 * (e.g. the agent loop on a worker thread while the SSE writer flushes frames), so streaming a
 * request no longer depends on a {@link ThreadLocal}. The legacy ThreadLocal accessors are kept
 * only as a fallback for callers/tests that run start and end on the same thread.
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
			return next(phase, status, actor, title, detail, parentId, agentId, depth, null);
		}

		/** As above with an explicit conversation role (null = derive from the phase). */
		public PipelineEvent next(String phase, String status, String actor, String title, String detail, Long parentId,
				String agentId, int depth, String role) {
			return append(phase, status, actor, title, detail, System.currentTimeMillis(), null, parentId, agentId,
					depth, role);
		}

		/** A step that completes immediately (e.g. JSON parsing) with a measured duration. */
		public PipelineEvent done(String phase, String status, String actor, String title, String detail,
				long durationMs) {
			return done(phase, status, actor, title, detail, durationMs, null, 0);
		}

		public PipelineEvent done(String phase, String status, String actor, String title, String detail,
				long durationMs, String agentId, int depth) {
			return done(phase, status, actor, title, detail, durationMs, agentId, depth, null);
		}

		public PipelineEvent done(String phase, String status, String actor, String title, String detail,
				long durationMs, String agentId, int depth, String role) {
			return append(phase, status, actor, title, detail, System.currentTimeMillis() - Math.max(0, durationMs),
					durationMs, null, agentId, depth, role);
		}

		public synchronized PipelineEvent close(PipelineEvent event, String status, String title, String detail,
				long durationMs, Long parentId) {
			PipelineEvent updated = new PipelineEvent(event.id(), event.traceId(), event.phase(), event.actor(), status,
					title, detail, event.startedAt(), durationMs, parentId,
					event.agentId(), event.depth() == null ? 0 : event.depth(), event.role());
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
				String detail, long startedAt, Long durationMs, Long parentId, String agentId, int depth,
				String role) {
			PipelineEvent event = new PipelineEvent(counter.incrementAndGet(), traceId, phase, actor, status, title,
					detail, startedAt, durationMs, parentId, agentId, depth, role == null ? defaultRole(phase) : role);
			events.add(event);
			return event;
		}

		/** Conversation role the trajectory UI assumes when no explicit role was given. */
		private static String defaultRole(String phase) {
			return switch (phase) {
				case PipelineEvent.PHASE_LLM -> PipelineEvent.ROLE_ASSISTANT;
				case PipelineEvent.PHASE_TOOL, PipelineEvent.PHASE_PARSE -> PipelineEvent.ROLE_TOOL;
				default -> null;
			};
		}

		synchronized PipelineEvent[] snapshot() {
			return events.toArray(PipelineEvent[]::new);
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
		return builder == null ? new PipelineEvent[0] : builder.snapshot();
	}

	public static void clear() {
		CURRENT.remove();
	}
}
