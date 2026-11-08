package com.panic.controller;

import com.panic.pipeline.PipelineEvent;
import com.panic.pipeline.PipelineJson;
import com.panic.pipeline.agui.AgUiEventEncoder;
import com.panic.pipeline.agui.AgUiStreamListener;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Listens to one chat run and pushes AG-UI wire events down its SSE response. Thread-safe:
 * answer deltas stream from model threads while lifecycle events arrive from the worker,
 * so opening/closing the assistant message is guarded.
 */
final class AgUiChatStream implements AgUiStreamListener {

	private static final AgUiEventEncoder ENCODER = new AgUiEventEncoder();

	private final SseEmitter emitter;
	private final String threadId;
	private final long runId;
	/** One streamed assistant answer per run keeps a stable message id. */
	private final String messageId;

	private final AtomicBoolean runOpen = new AtomicBoolean(true);
	private boolean textOpen;
	/** Tool steps already visited, keyed by `tc-<eventId>` — first sight emits the start. */
	private final Set<String> seenToolCalls = new HashSet<>();
	/** Nested agent runs opened by agent-executed tool calls, keyed by `sa-<toolCallKey>`. */
	private final Set<String> subagentRuns = new HashSet<>();

	AgUiChatStream(SseEmitter emitter, String threadId, long runId) {
		this.emitter = emitter;
		this.threadId = threadId;
		this.runId = runId;
		this.messageId = "m-" + runId;
	}

	/** Opening event: names the conversation and the trace the stream carries. */
	void runStarted() {
		emit(ENCODER.runStarted(threadId, runId));
	}

	@Override
	public synchronized void answerDelta(String text, String agentId) {
		if (!runOpen.get() || text == null || text.isEmpty()) {
			return;
		}
		if (!textOpen) {
			emit(ENCODER.textMessageStart(messageId));
			textOpen = true;
		}
		emit(ENCODER.textMessageContent(messageId, text));
	}

	@Override
	public synchronized void pipelineStep(PipelineEvent event) {
		if (!runOpen.get() || event == null) {
			return;
		}
		if (PipelineEvent.PHASE_TOOL.equals(event.phase())) {
			emitToolCall(event);
			return;
		}
		emit(ENCODER.pipelineStep(event));
	}

	/**
	 * A domain tool step by its visit order: the first sight starts the call (and, when an agent
	 * executes it, opens the nested run), the running copy carries the argument payload, the
	 * finished copy closes the call and reports the outcome (closing the nested run's work).
	 * One step in, the events it needs out; no reordering of the recorder's trace order.
	 */
	private void emitToolCall(PipelineEvent event) {
		String toolCallId = "tc-" + event.id();
		String subagentRunId = event.agentId() == null ? null : "sa-" + toolCallId;
		if (subagentRunId != null && subagentRuns.add(subagentRunId)) {
			emit(ENCODER.subagentStarted(subagentRunId, event.agentId(), toolCallId));
		}
		if (seenToolCalls.add(toolCallId)) {
			// The call rides the parent-owned assistant message, so it must stay in the
			// parent lane: a tool call belongs to the message that carries it, and the
			// event verifier rejects a subagent tag that does not match that message's
			// owner. The subagent itself is announced by SUBAGENT_STARTED above, anchored
			// to this call by its parentToolCallId.
			emit(ENCODER.toolCallStart(toolCallId, event.actor(), null, messageId));
		}
		if (PipelineEvent.STATUS_RUNNING.equals(event.status())) {
			emit(ENCODER.toolCallArgs(toolCallId, event.detail()));
			return;
		}
		emit(ENCODER.toolCallEnd(toolCallId));
		emit(ENCODER.toolCallResult("mtr-" + event.id(), toolCallId, event.detail()));
		if (subagentRunId != null) {
			emit(ENCODER.subagentFinished(subagentRunId, event.detail()));
		}
	}

	/** Closes the streaming assistant message; a no-op when no delta ever opened it. */
	private synchronized void endText() {
		if (!textOpen) {
			return;
		}
		textOpen = false;
		emit(ENCODER.textMessageEnd(messageId));
	}

	/** Success terminal: last delta lands, then RUN_FINISHED with the trace's totals. */
	void runFinished(long durationMs) {
		endText();
		if (runOpen.compareAndSet(true, false)) {
			emit(ENCODER.runFinished(threadId, runId, durationMs));
		}
	}

	/** Failure terminal: last delta lands, then RUN_ERROR (the status line stays 200). */
	void runFailed(String message, String code) {
		endText();
		if (runOpen.compareAndSet(true, false)) {
			emit(ENCODER.runError(message, code));
		}
	}

	private void emit(Map<String, Object> event) {
		try {
			// UTF-8 bytes, never the response-charset default: SSE JSON is UTF-8 per spec.
			byte[] payload = PipelineJson.json(event).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			emitter.send(SseEmitter.event().data(payload, MediaType.APPLICATION_JSON));
		} catch (IOException | IllegalStateException ex) {
			emitter.completeWithError(ex);
		}
	}
}
