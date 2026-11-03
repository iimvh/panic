package com.meetingroom.core.agent.runtime;

import org.springframework.util.StringUtils;

/**
 * Checks whether a real API key is configured.
 * <p>
 * {@code OpenAiChatModel} cannot be created with a blank key, so the app uses a placeholder to
 * keep the bean alive; this is the single place that decides whether agents may call the LLM.
 */
public final class AgentApiKey {

	/** Default value in {@code application.properties} when env OPENROUTER_API_KEY is missing. */
	public static final String PLACEHOLDER = "missing-openrouter-api-key";

	private AgentApiKey() {
	}

	public static boolean isConfigured(String apiKey) {
		return StringUtils.hasText(apiKey) && !PLACEHOLDER.equals(apiKey.trim());
	}
}
