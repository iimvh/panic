package com.panic.pipeline.agui;

import com.panic.pipeline.PipelineEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the AG-UI protocol wire events (docs.ag-ui.com, spec 1.0) the chat endpoint streams.
 * Every returned map serializes as exactly one SSE `data:` frame — one protocol event, never a
 * fragment. `threadId` names the conversation, `runId` the trace. Spring MVC adds the
 * `data:` framing; this class only fills the JSON payload. Pure translation: no I/O, no state.
 */
public final class AgUiEventEncoder {

	/** Name of the `CUSTOM` data part carrying raw recorded pipeline steps for the ledger. */
	public static final String PIPELINE_DATA_PART = "panic.pipeline";

	public AgUiEventEncoder() {
	}

	/** Opens the run; the pair is echoed back by the client on every event of this stream. */
	public Map<String, Object> runStarted(String threadId, long runId) {
		Map<String, Object> event = base("RUN_STARTED", threadId, runId);
		return event;
	}

	public Map<String, Object> textMessageStart(String messageId) {
		Map<String, Object> event = base("TEXT_MESSAGE_START", messageId);
		event.put("role", "assistant");
		return event;
	}

	public Map<String, Object> textMessageContent(String messageId, String delta) {
		Map<String, Object> event = base("TEXT_MESSAGE_CONTENT", messageId);
		event.put("delta", delta);
		return event;
	}

	public Map<String, Object> textMessageEnd(String messageId) {
		return base("TEXT_MESSAGE_END", messageId);
	}

	/** Closes the run; `metadata` is open-by-key and tells the ledger the trace's totals. */
	public Map<String, Object> runFinished(String threadId, long runId, long durationMs) {
		Map<String, Object> event = base("RUN_FINISHED", threadId, runId);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("traceId", runId);
		metadata.put("durationMs", durationMs);
		event.put("metadata", metadata);
		return event;
	}

	/** After-the-stream-opened failure: the HTTP status is already 200, so the error travels here. */
	public Map<String, Object> runError(String message, String code) {
		Map<String, Object> event = base("RUN_ERROR", null, null);
		event.put("message", message);
		event.put("code", code);
		return event;
	}

	/**
	 * One recorded pipeline step as a verbatim domain record inside a `CUSTOM` data part
	 * (`name`: {@link #PIPELINE_DATA_PART}). Every step lands here in trace-id order; the
	 * dedicated tool/subagent events below are drawn from the same stream by the chat stream.
	 * A part without a registered renderer is simply not displayed.
	 */
	public Map<String, Object> pipelineStep(PipelineEvent event) {
		Map<String, Object> wire = base("CUSTOM", null, null);
		wire.put("name", PIPELINE_DATA_PART);
		wire.put("value", event.toMap());
		return wire;
	}

	/**
	 * First third of a tool call: proposes the call. `subagentRunId` must match the owner of
	 * the message carrying the call — a subagent tag on a parent-owned message is rejected
	 * by the wire verifier; pass `null` for parent-lane calls.
	 */
	public Map<String, Object> toolCallStart(String toolCallId, String toolCallName, String subagentRunId,
			String parentMessageId) {
		Map<String, Object> event = base("TOOL_CALL_START", null, null);
		event.put("toolCallId", toolCallId);
		event.put("toolCallName", toolCallName);
		event.put("parentMessageId", parentMessageId);
		if (subagentRunId != null) {
			event.put("subagentRunId", subagentRunId);
		}
		return event;
	}

	/** Second third: the streamed argument payload (for domain calls, its `detail` JSON). */
	public Map<String, Object> toolCallArgs(String toolCallId, String delta) {
		Map<String, Object> event = base("TOOL_CALL_ARGS", null, null);
		event.put("toolCallId", toolCallId);
		event.put("delta", delta);
		event.put("timestamp", System.currentTimeMillis());
		return event;
	}

	/** Last third: the call's arguments are final. */
	public Map<String, Object> toolCallEnd(String toolCallId) {
		Map<String, Object> event = base("TOOL_CALL_END", null, null);
		event.put("toolCallId", toolCallId);
		event.put("timestamp", System.currentTimeMillis());
		return event;
	}

	/** The executed outcome, as a tool-role conversation record. */
	public Map<String, Object> toolCallResult(String messageId, String toolCallId, String content) {
		Map<String, Object> event = base("TOOL_CALL_RESULT", null, null);
		event.put("messageId", messageId);
		event.put("toolCallId", toolCallId);
		event.put("role", "tool");
		event.put("content", content);
		event.put("timestamp", System.currentTimeMillis());
		return event;
	}

	/** Opens a nested agent run: the parent's tool call id anchors it to a chat turn. */
	public Map<String, Object> subagentStarted(String subagentRunId, String name, String parentToolCallId) {
		Map<String, Object> event = base("SUBAGENT_STARTED", null, null);
		event.put("subagentRunId", subagentRunId);
		event.put("name", name);
		event.put("parentToolCallId", parentToolCallId);
		event.put("timestamp", System.currentTimeMillis());
		return event;
	}

	/** Closes a nested agent run with what it returned to its parent. */
	public Map<String, Object> subagentFinished(String subagentRunId, String result) {
		Map<String, Object> event = base("SUBAGENT_FINISHED", null, null);
		event.put("subagentRunId", subagentRunId);
		event.put("result", result);
		event.put("timestamp", System.currentTimeMillis());
		return event;
	}

	private Map<String, Object> base(String type, Object idMarker) {
		Map<String, Object> event = new LinkedHashMap<String, Object>();
		event.put("type", type);
		if (idMarker != null) {
			event.put("messageId", idMarker);
		}
		return event;
	}

	private Map<String, Object> base(String type, String threadId, Long runId) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", type);
		if (threadId != null) {
			event.put("threadId", threadId);
		}
		if (runId != null) {
			// AG-UI declares runId a string: JSON numbers fail the SSE event validation.
			event.put("runId", String.valueOf(runId));
		}
		event.put("timestamp", System.currentTimeMillis());
		return event;
	}
}
