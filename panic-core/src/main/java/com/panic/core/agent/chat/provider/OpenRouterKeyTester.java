package com.panic.core.agent.chat.provider;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Checks an OpenRouter credential against the provider itself, so Settings can
 * verify a key before the workspace saves or reconnects on it. Runs as a live,
 * unauthenticated-endpoint call (OpenRouter answers 401 for unknown keys).
 */
@Service
public class OpenRouterKeyTester {

	private static final Logger log = LoggerFactory.getLogger(OpenRouterKeyTester.class);

	private final RestClient openrouter;

	public OpenRouterKeyTester(RestClient.Builder restClientBuilder) {
		this.openrouter = restClientBuilder.build();
	}

	/**
	 * Verifies the key against OpenRouter's own key info endpoint, so Settings
	 * learns the verdict before the workspace saves or reconnects on it. Rejects
	 * blank keys outright. Never throws: the caller renders the outcome.
	 */
	public TestOutcome check(String apiKey) {
		if (!StringUtils.hasText(apiKey)) {
			return new TestOutcome(false, "API key must not be blank");
		}
		try {
			Map<?, ?> response = openrouter.get()
					.uri(LlmProviderKind.OPENROUTER.apiRoot() + "/key")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.strip())
					.retrieve()
					.body(Map.class);
			return new TestOutcome(true, describeKey(response));
		} catch (HttpStatusCodeException ex) {
			if (ex.getStatusCode().value() == 401) {
				return new TestOutcome(false, "OpenRouter rejected the key");
			}
			return new TestOutcome(false, "OpenRouter answered " + ex.getStatusCode().value());
		} catch (RuntimeException ex) {
			log.warn("OpenRouter key check failed: {}", ex.getMessage());
			return new TestOutcome(false, "OpenRouter unreachable: " + ex.getMessage());
		}
	}

	private static String describeKey(Map<?, ?> response) {
		if (response != null && response.get("data") instanceof Map<?, ?> key) {
			Object label = key.get("label");
			if (label instanceof String text && !text.isBlank()) {
				return "Key connected ('" + text + "')";
			}
		}
		return "Key connected";
	}

	/** One probe's verdict: whether the workspace may run on it and why. */
	public record TestOutcome(boolean ok, String detail) {
	}
}
