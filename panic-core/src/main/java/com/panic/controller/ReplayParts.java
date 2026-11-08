package com.panic.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panic.pipeline.PipelineEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds the AG-UI {@code content} parts of a replayed assistant turn from its recorded
 * pipeline, so a session re-opened from the store renders the same tool calls and final answer
 * the live stream showed. Each real tool call (an agent delegation, recorded as a {@code tool}
 * phase event titled {@code "Sub-agent <id>"}) becomes one {@code tool-call} part; the answer
 * closes the turn as a {@code text} part.
 */
final class ReplayParts {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SUB_AGENT_PREFIX = "Sub-agent ";

	private ReplayParts() {
	}

	/** Parts for one assistant turn: its tool calls in order, then the answer text (if any). */
	static List<Map<String, Object>> forTurn(List<PipelineEvent> pipeline, String answer) {
		// A tool call is recorded twice with the same id (running, then finished). Merge by id so
		// the args (on the running event) and the output/status (on the finished event) both survive.
		Map<Long, MergedTool> tools = new LinkedHashMap<>();
		if (pipeline != null) {
			for (PipelineEvent event : pipeline) {
				if (!PipelineEvent.PHASE_TOOL.equals(event.phase())) {
					continue;
				}
				if (event.title() == null || !event.title().startsWith(SUB_AGENT_PREFIX)) {
					continue;
				}
				tools.merge(event.id(), new MergedTool(event), (existing, next) -> existing.absorb(next));
			}
		}

		List<Map<String, Object>> parts = new ArrayList<>();
		for (MergedTool tool : tools.values()) {
			parts.add(tool.toPart());
		}
		if (answer != null && !answer.isBlank()) {
			Map<String, Object> text = new LinkedHashMap<>();
			text.put("type", "text");
			text.put("text", answer);
			parts.add(text);
		}
		return parts;
	}

	/** Merges the running + finished records of one tool call into a single {@code tool-call} part. */
	private static final class MergedTool {

		private final long id;
		private final String name;
		private JsonNode args;
		private String output;
		private boolean hasOutput;
		private boolean error;

		MergedTool(PipelineEvent event) {
			this.id = event.id();
			this.name = event.title().substring(SUB_AGENT_PREFIX.length()).trim();
			ingest(event);
		}

		/** Folds another record of the same tool call (running vs finished) into this one. */
		MergedTool absorb(MergedTool other) {
			if (args == null) {
				this.args = other.args;
			}
			if (!hasOutput && other.hasOutput) {
				this.output = other.output;
				this.hasOutput = true;
			}
			this.error = this.error || other.error;
			return this;
		}

		private void ingest(PipelineEvent event) {
			JsonNode detail = parse(event.detail());
			if (detail != null) {
				JsonNode a = detail.get("args");
				if (a != null && a.isObject()) {
					this.args = a;
				}
				JsonNode o = detail.get("output");
				if (o != null && !o.isNull()) {
					this.output = o.isTextual() ? o.asText() : o.toString();
					this.hasOutput = true;
				}
			}
			if (PipelineEvent.STATUS_ERROR.equals(event.status())) {
				this.error = true;
			}
		}

		Map<String, Object> toPart() {
			Map<String, Object> part = new LinkedHashMap<>();
			part.put("type", "tool-call");
			part.put("toolCallId", "replay-tool-" + id);
			part.put("toolName", name);
			part.put("args", args != null ? MAPPER.convertValue(args, Map.class) : Map.of());
			if (hasOutput) {
				part.put("result", output);
			}
			part.put("isError", error);
			return part;
		}
	}

	private static JsonNode parse(String detail) {
		if (detail == null || detail.isBlank()) {
			return null;
		}
		try {
			return MAPPER.readTree(detail);
		} catch (Exception ex) {
			return null;
		}
	}
}
