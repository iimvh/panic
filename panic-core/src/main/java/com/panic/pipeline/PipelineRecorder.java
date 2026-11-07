package com.panic.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Records each pipeline step of a request and broadcasts it in realtime via {@link PipelineBroadcaster}.
 * When a request has no open trace every method is a no-op (tools still run normally).
 */
@Component
public class PipelineRecorder {

	/** Tool call running on the current thread (so domain agents know their parentId). */
	private static final ThreadLocal<PipelineEvent> ACTIVE_TOOL = new ThreadLocal<>();
	private static final ThreadLocal<RequestTrace> CURRENT_TRACE = new ThreadLocal<>();
	/** Agent running on the current thread; local tools read it to tag their events with agentId/depth. */
	private static final ThreadLocal<ActiveAgent> ACTIVE_AGENT = new ThreadLocal<>();

	private final PipelineBroadcaster broadcaster;

	public PipelineRecorder(PipelineBroadcaster broadcaster) {
		this.broadcaster = broadcaster;
	}

	public boolean active() {
		return CURRENT_TRACE.get() != null;
	}

	public RequestTrace currentTrace() {
		return CURRENT_TRACE.get();
	}

	/**
	 * Binds a trace to the current thread. The chat endpoint starts the trace on the request thread
	 * and runs the agent on a worker; the worker must call this so recording sees the same trace.
	 */
	public void bind(RequestTrace trace) {
		CURRENT_TRACE.set(trace);
	}

	public void unbind() {
		CURRENT_TRACE.remove();
	}

	/**
	 * Runs a blocking task with a trace bound to the executing thread, restoring the previous
	 * binding afterwards. Streamed tool loops execute callbacks on reactor threads where no
	 * trace is bound; the executor captures the trace on the caller thread (already bound) and
	 * passes it here so synchronous sub-agent runs record into it.
	 */
	public <T> T callBound(RequestTrace trace, java.util.function.Supplier<T> task) {
		RequestTrace previous = CURRENT_TRACE.get();
		boolean bound = trace != null && trace != previous;
		if (bound) {
			CURRENT_TRACE.set(trace);
		}
		try {
			return task.get();
		} finally {
			if (bound) {
				if (previous == null) {
					CURRENT_TRACE.remove();
				} else {
					CURRENT_TRACE.set(previous);
				}
			}
		}
	}

	public PipelineEvent activeTool() {
		return ACTIVE_TOOL.get();
	}

	public void setActiveTool(PipelineEvent event) {
		if (event == null) {
			ACTIVE_TOOL.remove();
		} else {
			ACTIVE_TOOL.set(event);
		}
	}

	public PipelineEvent start(RequestTrace trace, String phase, String actor, String title, String detail) {
		return start(trace, phase, actor, title, detail, null);
	}

	public PipelineEvent start(RequestTrace trace, String phase, String actor, String title, String detail,
			Long parentId) {
		return start(trace, phase, actor, title, detail, parentId, null);
	}

	public PipelineEvent start(RequestTrace trace, String phase, String actor, String title, String detail,
			Long parentId, String agentId) {
		return start(trace, phase, actor, title, detail, parentId, agentId, null);
	}

	/** As above with an explicit conversation role for the trajectory view. */
	public PipelineEvent start(RequestTrace trace, String phase, String actor, String title, String detail,
			Long parentId, String agentId, String role) {
		if (trace == null) {
			return null;
		}
		return emit(trace, trace.builder().next(phase, PipelineEvent.STATUS_RUNNING, actor, title, detail, parentId,
				agentId, currentDepth(), role));
	}

	/** Marks the running agent/depth on the thread; returns the previous value for restore. */
	public ActiveAgent pushAgent(String agentId, int depth, Long parentEventId) {
		ActiveAgent previous = ACTIVE_AGENT.get();
		ACTIVE_AGENT.set(new ActiveAgent(agentId, depth, parentEventId));
		return previous;
	}

	public void popAgent(ActiveAgent previous) {
		if (previous == null) {
			ACTIVE_AGENT.remove();
		} else {
			ACTIVE_AGENT.set(previous);
		}
	}

	public ActiveAgent activeAgent() {
		return ACTIVE_AGENT.get();
	}

	public int currentDepth() {
		ActiveAgent agent = ACTIVE_AGENT.get();
		return agent == null ? 0 : agent.depth();
	}

	/** Agent running on the thread: id, depth, and the parent event its LLM events belong under. */
	public record ActiveAgent(String agentId, int depth, Long parentEventId) {
	}

	public PipelineEvent finish(RequestTrace trace, PipelineEvent event, String status, String title, String detail,
			long durationMs) {
		return finish(trace, event, status, title, detail, durationMs, event == null ? null : event.parentId());
	}

	/** Closes an event; pass parentId explicitly when the parent/child relation changes. */
	public PipelineEvent finish(RequestTrace trace, PipelineEvent event, String status, String title, String detail,
			long durationMs, Long parentId) {
		if (trace == null || event == null) {
			return event;
		}
		if (trace.builder() != null) {
			return emit(trace, trace.builder().close(event, status, title, detail, durationMs, parentId));
		}
		// An event may be created while the trace is open but closed on another thread: still update it so the UI does not stay "running".
		return emit(trace, new PipelineEvent(event.id(), event.traceId(), event.phase(), event.actor(), status,
					title, detail, event.startedAt(), durationMs, parentId, event.agentId(),
					event.depth() == null ? 0 : event.depth(), event.role()));
	}

	public PipelineEvent emit(RequestTrace trace, PipelineEvent event) {
		if (trace != null && event != null) {
			trace.add(event);
			broadcaster.publish(event);
		}
		return event;
	}

	/** Step that completes immediately (e.g. JSON parsing); no prior "running" event needed. */
	public PipelineEvent emitDone(RequestTrace trace, String phase, String status, String actor, String title,
			String detail, long durationMs) {
		return emitDone(trace, phase, status, actor, title, detail, durationMs, null);
	}

	/** As above, additionally tagging the running agent's agentId/depth (null = request/coordinator event). */
	public PipelineEvent emitDone(RequestTrace trace, String phase, String status, String actor, String title,
			String detail, long durationMs, String agentId) {
		return emitDone(trace, phase, status, actor, title, detail, durationMs, agentId, null);
	}

	/** As above with an explicit conversation role for the trajectory view. */
	public PipelineEvent emitDone(RequestTrace trace, String phase, String status, String actor, String title,
			String detail, long durationMs, String agentId, String role) {
		if (trace == null || trace.builder() == null) {
			return null;
		}
		return emit(trace, trace.builder().done(phase, status, actor, title, detail, durationMs, agentId,
				currentDepth(), role));
	}

	/** Opens a trace for the current request; returns the JSON header incl. the id the client uses for SSE. */
	public Map<String, Object> startResponse(RequestTrace trace) {
		CURRENT_TRACE.set(trace);
		PipelineTrace.Builder builder = trace.begin();
		PipelineEvent event = emit(trace, builder.next(PipelineEvent.PHASE_TRACE, PipelineEvent.STATUS_RUNNING,
				"coordinator", "Request received", "{\"requestId\":" + trace.id() + "}", null, null, 0,
				PipelineEvent.ROLE_SYSTEM));
		if (event != null) {
			trace.markRunning(event);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("traceId", trace.id());
		body.put("traceStartId", event == null ? 0 : event.id());
		return body;
	}

	/**
	 * Ends the trace: closes the root event, emits the final SSE frame, returns all events.
	 * Called in a finally block, so it always runs, even on error.
	 */
	public PipelineEvent[] end(RequestTrace trace, boolean failed, String error) {
		PipelineTrace.Builder builder = trace.builder();
		PipelineEvent[] pending = trace.pendingTraceEvent();
		if (builder != null) {
			for (PipelineEvent event : pending) {
				emit(trace, builder.close(event, failed ? PipelineEvent.STATUS_ERROR : PipelineEvent.STATUS_OK,
						event.title(), event.detail(), trace.durationMs(), event.parentId()));
			}
		}
		PipelineEvent[] events = trace.finish();
		if (failed) {
			// The step a run died in stays "running" forever unless closed here — close it with the
			// failure reason so the UI (and the persisted trace) shows exactly which step failed.
			for (int i = 0; i < events.length; i++) {
				if (PipelineEvent.STATUS_RUNNING.equals(events[i].status())) {
					events[i] = events[i].with(PipelineEvent.STATUS_ERROR, events[i].title(), error,
							trace.durationMs());
				}
			}
		}
		ACTIVE_TOOL.remove();
		ACTIVE_AGENT.remove();
		CURRENT_TRACE.remove();
		return events;
	}

	/**
	 * Trace of one HTTP request; the id is returned to the client as soon as it starts.
	 */
	public static final class RequestTrace {

		private final long id;
		private final AtomicBoolean closed = new AtomicBoolean();
		private final Map<Long, PipelineEvent> events = new LinkedHashMap<>();
		private long startedAt;
		private long traceStartId;
		/** Owned by this trace, so event recording works even when start and emit run on different threads. */
		private PipelineTrace.Builder builder;

		public RequestTrace() {
			this.id = System.nanoTime();
		}

		public long id() {
			return id;
		}

		/** The event builder of this request; {@code null} until {@link PipelineRecorder#startResponse}. */
		public synchronized PipelineTrace.Builder builder() {
			return builder;
		}

		synchronized PipelineTrace.Builder begin() {
			if (builder == null) {
				builder = new PipelineTrace.Builder(id);
			}
			startedAt = System.currentTimeMillis();
			return builder;
		}

		/** Root "Request received" event — closed at the end of the trace so the UI can show total time. */
		private PipelineEvent rootEvent;

		synchronized void markRunning(PipelineEvent event) {
			rootEvent = event;
		}

		synchronized PipelineEvent[] pendingTraceEvent() {
			return rootEvent == null ? new PipelineEvent[0] : new PipelineEvent[] { rootEvent };
		}

		synchronized void add(PipelineEvent event) {
			PipelineEvent existing = events.get(event.id());
			// Under concurrency a "running" copy can arrive after a "finished" copy: keep the closed one.
			if (existing != null && existing.status().equals(PipelineEvent.STATUS_RUNNING)) {
				return;
			}
			if (rootEvent != null && rootEvent.id() == event.id()
					&& !PipelineEvent.STATUS_RUNNING.equals(event.status())) {
				rootEvent = null;
			}
			events.put(event.id(), event);
			if (event.phase().equals(PipelineEvent.PHASE_TRACE) && traceStartId == 0) {
				traceStartId = event.id();
			}
		}

		synchronized PipelineEvent[] finish() {
			if (!closed.compareAndSet(false, true)) {
				return snapshot();
			}
			if (builder != null) {
				for (PipelineEvent event : builder.snapshot()) {
					events.put(event.id(), event);
				}
			}
			return snapshot();
		}

		private PipelineEvent[] snapshot() {
			return events.values().toArray(PipelineEvent[]::new);
		}

		public long durationMs() {
			return startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
		}

		public synchronized List<PipelineEvent> currentEvents() {
			return List.copyOf(events.values());
		}
	}
}
