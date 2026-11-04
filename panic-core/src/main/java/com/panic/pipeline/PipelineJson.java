package com.panic.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Builds the `detail` JSON for {@link PipelineEvent}; also used to write /chat SSE frames. */
public final class PipelineJson {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MAX_TEXT = 4000;

	private PipelineJson() {
	}

	public static String of(Object value) {
		try {
			return trim(MAPPER.writeValueAsString(value));
		} catch (Exception ex) {
			return text("<unserializable " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ">");
		}
	}

	/** Unbounded serialization for the payload returned to clients. */
	public static String json(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception ex) {
			return "{\"error\":\"" + ex.getClass().getSimpleName() + "\"}";
		}
	}

	/** Raw string (e.g. tool output) — trimmed for brevity. */
	public static String text(String raw) {
		return trim(raw);
	}

	private static String trim(String value) {
		if (value == null) {
			return "";
		}
		return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT) + "…";
	}
}
