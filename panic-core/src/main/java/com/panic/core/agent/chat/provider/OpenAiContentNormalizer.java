package com.panic.core.agent.chat.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Normalizes the OpenAI-compatible response body so Spring AI 1.0.0 can read it.
 * <p>
 * Spring AI's {@code OpenAiApi.ChatCompletionMessage.content()} accepts only a JSON string (or
 * null); some providers — Mistral's {@code leanstral} models, for one — return {@code content} as
 * an <b>array of content parts</b> ({@code [{"type":"text","text":"..."}]}), which makes
 * {@code content()} throw {@code IllegalStateException: The content is not a string!}. This rewrites
 * such arrays to the concatenated text (and an empty array / null to {@code ""}) before parsing,
 * for both a plain JSON body and an SSE stream where each {@code data:} line is its own JSON.
 * <p>
 * Everything except the {@code content} field is left untouched. Malformed input is returned as-is,
 * so the normalizer can never make a working response worse.
 */
final class OpenAiContentNormalizer {

	private static final Logger log = LoggerFactory.getLogger(OpenAiContentNormalizer.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private OpenAiContentNormalizer() {
	}

	/** Normalizes a whole response body: SSE ({@code data:} lines) or a single JSON object. */
	static String normalizeBody(String body) {
		if (body == null || body.isEmpty()) {
			return body;
		}
		if (body.indexOf("data:") >= 0 && (body.startsWith("data:") || body.contains("\ndata:"))) {
			return normalizeSse(body);
		}
		return normalizeJsonObject(body);
	}

	private static String normalizeSse(String body) {
		StringBuilder out = new StringBuilder(body.length());
		String[] lines = body.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			if (line.startsWith("data:")) {
				String payload = line.substring("data:".length());
				if (!payload.isBlank() && !"[DONE]".equals(payload.trim())) {
					line = "data:" + normalizeJsonObject(payload);
				}
			}
			out.append(line);
			if (i < lines.length - 1) {
				out.append('\n');
			}
		}
		return out.toString();
	}

	private static String normalizeJsonObject(String json) {
		try {
			JsonNode root = MAPPER.readTree(json);
			if (!(root instanceof ObjectNode object)) {
				return json;
			}
			boolean changed = false;
			JsonNode choices = object.get("choices");
			if (choices instanceof ArrayNode choiceArray) {
				for (JsonNode choice : choiceArray) {
					if (!(choice instanceof ObjectNode choiceObject)) {
						continue;
					}
					changed |= normalizeOn(choiceObject, "message");
					changed |= normalizeOn(choiceObject, "delta");
				}
			}
			return changed ? MAPPER.writeValueAsString(root) : json;
		} catch (Exception ex) {
			log.debug("Skipping response normalization (unparseable body): {}", ex.getMessage());
			return json;
		}
	}

	/** Rewrites {@code parent.<field>.content} from an array/null to a plain string. */
	private static boolean normalizeOn(ObjectNode parent, String field) {
		JsonNode node = parent.get(field);
		if (!(node instanceof ObjectNode message)) {
			return false;
		}
		JsonNode content = message.get("content");
		if (content == null) {
			return false;
		}
		if (content.isTextual()) {
			return false;
		}
		if (content.isNull()) {
			message.put("content", "");
			return true;
		}
		if (content.isArray()) {
			message.set("content", TextNode.valueOf(joinTextParts(content)));
			return true;
		}
		// Any other shape (number/object): coerce to its string form so content() never throws.
		message.set("content", TextNode.valueOf(content.asText()));
		return true;
	}

	/** Concatenates the {@code text} of every text part; non-text parts contribute nothing. */
	private static String joinTextParts(JsonNode parts) {
		StringBuilder text = new StringBuilder();
		for (JsonNode part : parts) {
			if (part.isTextual()) {
				text.append(part.asText());
				continue;
			}
			JsonNode value = part.get("text");
			if (value != null && value.isTextual()) {
				text.append(value.asText());
			}
		}
		return text.toString();
	}
}
