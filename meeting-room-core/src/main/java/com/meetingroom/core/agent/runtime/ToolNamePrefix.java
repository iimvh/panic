package com.meetingroom.core.agent.runtime;

/**
 * Tool names the LLM sees always look like {@code <agentId>__<rawName>}.
 * Spring AI's {@code McpToolNamePrefixGenerator} only deduplicates across MCP connections and
 * does not cover local {@code @Tool}s, so the core must prefix names itself to avoid clashes.
 */
public final class ToolNamePrefix {

	public static final String SEPARATOR = "__";
	public static final int MAX_LENGTH = 64;

	private ToolNamePrefix() {
	}

	public static String apply(String agentId, String rawName) {
		String agent = sanitize(agentId, "agent");
		String raw = sanitize(rawName, "tool");
		String candidate = agent + SEPARATOR + raw;
		if (candidate.length() <= MAX_LENGTH) {
			return candidate;
		}
		int budget = MAX_LENGTH - SEPARATOR.length() - raw.length();
		if (budget <= 0) {
			// rawName takes almost the whole limit: keep rawName intact and cut agentId to 1 character.
			raw = raw.substring(0, MAX_LENGTH - SEPARATOR.length() - 1);
			budget = 1;
		}
		return agent.substring(0, budget) + SEPARATOR + raw;
	}

	public static String agentOf(String toolName) {
		int index = toolName == null ? -1 : toolName.indexOf(SEPARATOR);
		return index <= 0 ? null : toolName.substring(0, index);
	}

	/** Characters outside {@code [a-zA-Z0-9_-]} become {@code _}; keeps names valid for LLM/tool APIs. */
	public static String sanitize(String value, String fallback) {
		String cleaned = value == null ? "" : value.replaceAll("[^a-zA-Z0-9_-]", "_");
		return cleaned.isEmpty() ? fallback : cleaned;
	}
}
