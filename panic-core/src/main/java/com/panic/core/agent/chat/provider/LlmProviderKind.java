package com.panic.core.agent.chat.provider;

/** Connectable LLM backends; each carries its OpenAI-compatible endpoint root. */
public enum LlmProviderKind {

	/** Spring AI appends {@code /v1/chat/completions} itself, so the base host stays bare. */
	OPENROUTER("https://openrouter.ai/api", "https://openrouter.ai/api/v1");

	private final String baseUrl;
	private final String apiRoot;

	LlmProviderKind(String baseUrl, String apiRoot) {
		this.baseUrl = baseUrl;
		this.apiRoot = apiRoot;
	}

	/** Base url the OpenAI-compatible chat client is built on. */
	public String baseUrl() {
		return baseUrl;
	}

	/** Root address of the provider's own REST surface (key check, model catalog). */
	public String apiRoot() {
		return apiRoot;
	}

	/** Wire id clients exchange in {@code provider} fields; case-insensitive on read. */
	public String externalId() {
		return name().toLowerCase();
	}

	/** Parses a client-sent provider id into a kind; null when unknown. */
	public static LlmProviderKind ofExternalId(String externalId) {
		if (externalId == null) {
			return null;
		}
		for (LlmProviderKind kind : values()) {
			if (kind.externalId().equals(externalId.strip().toLowerCase())) {
				return kind;
			}
		}
		return null;
	}
}
