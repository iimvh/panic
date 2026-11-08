package com.panic.controller;

import com.panic.core.agent.chat.provider.LlmProviderKind;
import com.panic.core.agent.chat.provider.LlmProviderStore;
import com.panic.core.agent.chat.provider.OpenRouterFreeModelCatalog;
import com.panic.core.agent.chat.provider.OpenRouterKeyTester;
import com.panic.exception.BookingException;
import java.util.List;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings panel's API: connect a provider (API key + model, stored in the
 * database, never in the environment), verify a key against its provider, list
 * the free models the composer can select and inspect the current connection
 * status — with only a masked hint of the stored key.
 */
@RestController
@RequestMapping("/api/provider")
public class LlmProviderController {

	/** Number of final key characters the status endpoint shows in its hint. */
	private static final int KEY_HINT_CHARS = 4;

	private final LlmProviderStore store;
	private final OpenRouterFreeModelCatalog models;
	private final OpenRouterKeyTester keyTester;

	public LlmProviderController(LlmProviderStore store, OpenRouterFreeModelCatalog models,
			OpenRouterKeyTester keyTester) {
		this.store = store;
		this.models = models;
		this.keyTester = keyTester;
	}

	/** Current connection: null provider ids while nothing is configured yet. */
	@GetMapping
	public LlmProviderStatus status() {
		return store.current()
				.map(LlmProviderController::statusOf)
				.orElse(LlmProviderStatus.DISCONNECTED);
	}

	/** Composer's list: free models only, so picks stay no-cost. */
	@GetMapping("/models")
	public List<OpenRouterFreeModelCatalog.FreeModel> freeModels() {
		return models.freeModels();
	}

	/**
	 * Connects (or reconfigures) a provider: a fresh key is verified against its provider
	 * first — a rejected key never lands in the database (422). A blank key keeps the
	 * currently stored one, so switching the model never asks for re-entry.
	 */
	@PutMapping
	public LlmProviderStatus connect(@RequestBody ProviderChange change) {
		LlmProviderKind kind = LlmProviderKind.ofExternalId(change.provider());
		if (kind == null) {
			throw new BookingException("Unknown provider '" + change.provider() + "'");
		}
		String apiKey = change.apiKey() == null || change.apiKey().isBlank()
				? reuseStoredKey(kind)
				: change.apiKey();
		if (!StringUtils.hasText(apiKey)) {
			// No stored credential to fall back on: connecting needs an actual key.
			throw new BookingException("API key must not be blank: enter a key to connect", 422);
		}
		if (!StringUtils.hasText(change.model())) {
			throw new BookingException("Model must not be blank", 422);
		}
		if (change.apiKey() == null || !change.apiKey().isBlank()) {
			OpenRouterKeyTester.TestOutcome check = keyTester.check(apiKey);
			if (!check.ok()) {
				// 422: the request is well-formed but the credential does not verify.
				throw new BookingException(check.detail(), 422);
			}
		}
		return statusOf(store.connect(kind, apiKey, change.model()));
	}

	/** The stored key of this provider; null when none is stored yet. */
	private String reuseStoredKey(LlmProviderKind kind) {
		return store.current()
				.filter(snapshot -> snapshot.kind() == kind)
				.map(LlmProviderStore.ProviderSnapshot::apiKey)
				.orElse(null);
	}

	/** Dry-run of a candidate key (blank = the currently stored one). */
	@PostMapping("/test")
	public TestResult test(@RequestBody KeyProbe probe) {
		String candidate = probe.apiKey() == null || probe.apiKey().isBlank()
				? store.current().map(LlmProviderStore.ProviderSnapshot::apiKey).orElse("")
				: probe.apiKey();
		OpenRouterKeyTester.TestOutcome check = keyTester.check(candidate);
		return new TestResult(check.ok(), check.detail());
	}

	private static LlmProviderStatus statusOf(LlmProviderStore.ProviderSnapshot snapshot) {
		return new LlmProviderStatus(snapshot.kind().externalId(), snapshot.model(),
				true, maskedHint(snapshot.apiKey()));
	}

	/** All but the last chars masked; the hint is a recognition aid, not a leak. */
	private static String maskedHint(String apiKey) {
		String tail = apiKey.substring(Math.max(0, apiKey.length() - KEY_HINT_CHARS));
		return "••••••••" + tail;
	}

	/** GET answer: the workspace's single connection, disconnected when absent. */
	public record LlmProviderStatus(String provider, String model, boolean connected, String keyHint) {

		public static final LlmProviderStatus DISCONNECTED = new LlmProviderStatus(null, null, false, null);
	}

	/** PUT body: provider id ("openrouter"), its API key and the chosen model id. */
	public record ProviderChange(String provider, String apiKey, String model) {
	}

	/** POST /test body: a candidate key, or blank for the stored one. */
	public record KeyProbe(String apiKey) {
	}

	/** POST /test answer: the live verdict and its reason. */
	public record TestResult(boolean ok, String detail) {
	}
}
