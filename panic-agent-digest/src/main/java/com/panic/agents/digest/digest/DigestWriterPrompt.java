package com.panic.agents.digest.digest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Loads the digest writer's editorial prompt from {@code prompts/digest-writer.md} and fills the
 * per-run values. The prompt is a file, so tone and structure change without a rebuild — the same
 * principle as every agent's {@code system.md}.
 */
final class DigestWriterPrompt {

	private static final String LOCATION = "prompts/digest-writer.md";

	private final String template;

	DigestWriterPrompt() {
		this.template = read();
	}

	String system() {
		int separator = template.indexOf("\n---\n");
		return separator < 0 ? template : template.substring(0, separator).trim();
	}

	String user(LocalDate date, int maxItems, int candidateCount, String payloadJson) {
		String userPart = template;
		int separator = template.indexOf("\n---\n");
		if (separator >= 0) {
			userPart = template.substring(separator + 5);
		}
		return userPart.trim()
				.replace("{date}", date.toString())
				.replace("{maxItems}", Integer.toString(maxItems))
				.replace("{candidateCount}", Integer.toString(candidateCount))
				.replace("{payload}", payloadJson);
	}

	private static String read() {
		org.springframework.core.io.ClassPathResource resource =
				new org.springframework.core.io.ClassPathResource(LOCATION);
		if (!resource.exists()) {
			throw new IllegalStateException("Missing digest writer prompt: " + LOCATION);
		}
		try (InputStream input = resource.getInputStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot read digest writer prompt '" + LOCATION + "'", ex);
		}
	}
}
