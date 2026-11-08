package com.panic.core.agent.chat.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * The settable model list behind the composer's selector: OpenRouter's catalog pared
 * down to free variants only. An id ending in {@code :free} (or zero prompt and
 * completion pricing) costs nothing, which is what running this workspace demands.
 * The provider call is cached briefly — the list is long, stable and public, so the
 * cache is an accelerator, never a correctness source.
 */
@Service
public class OpenRouterFreeModelCatalog {

	private static final Logger log = LoggerFactory.getLogger(OpenRouterFreeModelCatalog.class);

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);
	private static final String FREE_SUFFIX = ":free";

	private record CatalogCache(List<FreeModel> models, Instant expiresAt) {
	}

	private final RestClient openrouter;
	private volatile CatalogCache cache;

	public OpenRouterFreeModelCatalog(RestClient.Builder restClientBuilder) {
		this.openrouter = restClientBuilder.build();
	}

	/** Free models, id order; a provider outage answers the stale cache or an empty list. */
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
			Map<String, ?> response = openrouter.get()
					.uri(LlmProviderKind.OPENROUTER.apiRoot() + "/models")
					.retrieve()
					.body(Map.class);
			if (response == null || !(response.get("data") instanceof List<?> raw)) {
				return List.of();
			}
			Map<String, FreeModel> byId = new LinkedHashMap<>();
			for (Object entry : raw) {
				FreeModel model = toFreeModel(entry);
				if (model != null) {
					// Doubles appearing both ways (:free id plus zero-priced base id) keep one slot.
					byId.putIfAbsent(model.id(), model);
				}
			}
			return byId.values().stream().sorted(Comparator.comparing(FreeModel::id)).toList();
		} catch (RuntimeException ex) {
			log.warn("OpenRouter model catalog unavailable: {}", ex.getMessage());
			return List.of();
		}
	}

	/** One catalog entry, or null when it costs money or carries unusable fields. */
	private static FreeModel toFreeModel(Object entry) {
		if (!(entry instanceof Map<?, ?> model)) {
			return null;
		}
		Object id = model.get("id");
		if (!(id instanceof String modelId) || modelId.isBlank()) {
			return null;
		}
		if (isFree(modelId, model.get("pricing"))) {
			return new FreeModel(modelId, nameOf(model), contextTokensOf(model.get("context_length")));
		}
		return null;
	}

	private static boolean isFree(String modelId, Object pricing) {
		if (modelId.toLowerCase().endsWith(FREE_SUFFIX)) {
			return true;
		}
		// Zero-priced non-suffixed ids are free too; blanks count as free (not billed).
		if (pricing instanceof Map<?, ?> price) {
			return isZeroOrBlank(price.get("prompt")) && isZeroOrBlank(price.get("completion"));
		}
		return false;
	}

	private static boolean isZeroOrBlank(Object value) {
		return value == null || "".equals(String.valueOf(value).strip())
				|| "0".equals(String.valueOf(value).strip());
	}

	private static String nameOf(Map<?, ?> model) {
		Object name = model.get("name");
		return name instanceof String text && StringUtils.hasText(text) ? text : "model";
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
