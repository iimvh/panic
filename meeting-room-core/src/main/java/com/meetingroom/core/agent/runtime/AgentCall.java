package com.meetingroom.core.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingroom.exception.BookingException;

/** Reads the JSON arguments the LLM passes to dynamic tools ({@code call_<sub>}, {@code load_skill}). */
record AgentCall(String query, String skillId) {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	static AgentCall from(String json) {
		JsonNode node = parse(json);
		return new AgentCall(text(node, "query"), text(node, "skillId"));
	}

	static AgentCall queryOf(String json) {
		return from(json);
	}

	static String skillIdOf(String json) {
		String skillId = from(json).skillId();
		if (skillId == null || skillId.isBlank()) {
			throw new BookingException("Missing skillId");
		}
		return skillId;
	}

	private static JsonNode parse(String json) {
		if (json == null || json.isBlank()) {
			throw new BookingException("Missing tool arguments");
		}
		try {
			return MAPPER.readTree(json);
		} catch (Exception ex) {
			throw new BookingException("Tool arguments are not valid JSON: " + ex.getMessage());
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value == null || value.isNull() ? null : value.asText();
	}
}
