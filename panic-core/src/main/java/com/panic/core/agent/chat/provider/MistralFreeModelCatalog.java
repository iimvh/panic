package com.panic.core.agent.chat.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * The settable model list behind the composer's selector: Mistral's catalog
 * pared down to variants the workspace can run. Uses the currently stored
 * Mistral API key for authenticated requests. The provider call is cached
 * briefly — the list is long, stable and public, so the cache is an
 * accelerator, never a correctness source.
 */
@Service
public class MistralFreeModelCatalog {

	private static final Logger log = LoggerFactory.getLogger(MistralFreeModelCatalog.class);

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);

	private record CatalogCache(List<FreeModel> models, Instant expiresAt) {
	}

	private final LlmProviderStore store;
	private final RestClient mistral;
	private volatile CatalogCache cache;

	public MistralFreeModelCatalog(LlmProviderStore store, RestClient.Builder restClientBuilder) {
		this.store = store;
		this.mistral = restClientBuilder.build();
	}

	/** Models, id order; a provider outage answers the stale cache or an empty list. */
	public List<FreeModel> freeModels() {
		CatalogCache snapshot = cache;
		if (snapshot != null && Instant.now().isBefore(snapshot.expiresAt())) {
			return snapshot.models();
		}
		List<FreeModel> fetched = fetch();
		if (fetched.isEmpty() && snapshot != null) {
			return snapshot.models();
		}
		if (!fetched.isEmpty()) {
			cache = new CatalogCache(fetched, Instant.now().plus(CACHE_TTL));
		}
		return fetched;
	}

	private List<FreeModel> fetch() {
		try {
			String modelsUrl = LlmProviderKind.MISTRAL.apiRoot() + "/v1/models";
			String key = store.current()
					.filter(snapshot -> snapshot.kind() == LlmProviderKind.MISTRAL)
					.map(LlmProviderStore.ProviderSnapshot::apiKey)
					.filter(StringUtils::hasText)
					.orElse(null);
			Map<String, ?> response;
			if (key != null) {
				response = mistral.get()
						.uri(modelsUrl)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
						.retrieve()
						.body(Map.class);
			} else {
				response = mistral.get()
						.uri(modelsUrl)
						.retrieve()
						.body(Map.class);
			}
			if (response == null || !(response.get("data") instanceof List<?> raw)) {
				return List.of();
			}
			Map<String, FreeModel> byId = new LinkedHashMap<>();
			for (Object entry : raw) {
				FreeModel model = toFreeModel(entry);
				if (model != null) {
					byId.putIfAbsent(model.id(), model);
				}
			}
			return byId.values().stream().sorted(Comparator.comparing(FreeModel::id)).toList();
		} catch (RuntimeException ex) {
			log.warn("Mistral model catalog unavailable: {}", ex.getMessage());
			return List.of();
		}
	}

	private static FreeModel toFreeModel(Object entry) {
		if (!(entry instanceof Map<?, ?> model)) {
			return null;
		}
		Object id = model.get("id");
		if (!(id instanceof String modelId) || modelId.isBlank()) {
			return null;
		}
		return new FreeModel(modelId, nameOf(model), contextTokensOf(model.get("context_length")));
	}

	private static String nameOf(Map<?, ?> model) {
		Object name = model.get("name");
		return name instanceof String text && !text.isBlank() ? text : "model";
	}

	private static long contextTokensOf(Object contextLength) {
		if (contextLength instanceof Number tokens) {
			return tokens.longValue();
		}
		try {
			return contextLength == null ? 0 : Long.parseLong(String.valueOf(contextLength));
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	/** One composer-selectable model: the routing id, its display name and its context window. */
	public record FreeModel(String id, String name, long contextTokens) {
	}
}
