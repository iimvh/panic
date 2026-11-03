package com.meetingroom.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step in an agent's real execution pipeline (immutable):
 * request start, LLM request, tool call (name/args/id), tool result...
 * so the UI can draw it as a tree in realtime.
 * <p>
 * {@code agentId}/{@code depth} were added with the agent-plugin work; the frontend only reads the
 * extra keys, no existing field was dropped or renamed.
 */
public record PipelineEvent(
		long id,
		long traceId,
		String phase,
		String actor,
		String status,
		String title,
		String detail,
		long startedAt,
		Long durationMs,
		Long parentId,
		String agentId,
		Integer depth) {

	public static final String PHASE_TRACE = "trace";
	public static final String PHASE_LLM = "llm";
	public static final String PHASE_TOOL = "tool";
	public static final String PHASE_PARSE = "parse";
	public static final String PHASE_ERROR = "error";

	public static final String STATUS_RUNNING = "running";
	public static final String STATUS_OK = "ok";
	public static final String STATUS_ERROR = "error";

	/** Legacy constructor so agent-unaware code (e.g. request-level events) needs no change. */
	public PipelineEvent(long id, long traceId, String phase, String actor, String status, String title,
			String detail, long startedAt, Long durationMs, Long parentId) {
		this(id, traceId, phase, actor, status, title, detail, startedAt, durationMs, parentId, null, 0);
	}

	/** Creates a new copy with the same identity/timestamps but updated content. */
	public PipelineEvent with(String status, String title, String detail, Long durationMs) {
		return new PipelineEvent(id, traceId, phase, actor, status, title, detail, startedAt, durationMs, parentId,
				agentId, depth);
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", id);
		map.put("traceId", traceId);
		map.put("phase", phase);
		map.put("actor", actor);
		map.put("status", status);
		map.put("title", title);
		map.put("detail", detail);
		map.put("startedAt", startedAt);
		map.put("durationMs", durationMs);
		map.put("parentId", parentId);
		map.put("agentId", agentId);
		map.put("depth", depth);
		return map;
	}
}
