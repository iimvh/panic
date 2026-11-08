package com.panic.core.agent.chat.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Checks a Mistral credential against the provider itself, so Settings can
 * verify a key before the workspace saves or reconnects on it. Runs as a live
 * call to Mistral's models endpoint (unknown keys answer 401).
 */
@Service
public class MistralKeyTester {

	private static final Logger log = LoggerFactory.getLogger(MistralKeyTester.class);

	private final RestClient mistral;

	public MistralKeyTester(RestClient.Builder restClientBuilder) {
		this.mistral = restClientBuilder.build();
	}

	/**
	 * Verifies the key against Mistral's own models endpoint, so Settings learns
	 * the verdict before the workspace saves or reconnects on it. Rejects blank
	 * keys outright. Never throws: the caller renders the outcome.
	 */
	public TestOutcome check(String apiKey) {
		if (!StringUtils.hasText(apiKey)) {
			return new TestOutcome(false, "API key must not be blank");
		}
		try {
			mistral.get()
					.uri(LlmProviderKind.MISTRAL.apiRoot() + "/v1/models")
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.strip())
					.retrieve()
					.toBodilessEntity();
			return new TestOutcome(true, "Key connected");
		} catch (HttpStatusCodeException ex) {
			if (ex.getStatusCode().value() == 401) {
				return new TestOutcome(false, "Mistral rejected the key");
			}
			return new TestOutcome(false, "Mistral answered " + ex.getStatusCode().value());
		} catch (RuntimeException ex) {
			log.warn("Mistral key check failed: {}", ex.getMessage());
			return new TestOutcome(false, "Mistral unreachable: " + ex.getMessage());
		}
	}

	/** One probe's verdict: whether the workspace may run on it and why. */
	public record TestOutcome(boolean ok, String detail) {
	}
}
