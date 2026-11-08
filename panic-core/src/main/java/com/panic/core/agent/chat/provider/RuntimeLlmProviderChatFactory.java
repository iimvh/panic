package com.panic.core.agent.chat.provider;

import java.net.HttpURLConnection;
import com.panic.exception.BookingException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The production {@link LlmProviderChatFactory}: rebuilds (and caches) the chat
 * model whenever the active provider row changes, so a Settings-panel save on
 * key/model takes over from the next request while an in-flight run keeps its
 * own already-built client.
 */
@Component
public class RuntimeLlmProviderChatFactory implements LlmProviderChatFactory {

	private final LlmProviderStore store;
	private final double temperature;
	private final int maxTokens;
	/** Cached client for the currently active snapshot; reverts to null once outdated. */
	private volatile CachedClient cached;

	public RuntimeLlmProviderChatFactory(LlmProviderStore store,
			@Value("${app.provider.chat-temperature:0.2}") double temperature,
			@Value("${app.provider.chat-max-tokens:4096}") int maxTokens) {
		this.store = store;
		this.temperature = temperature;
		this.maxTokens = maxTokens;
	}

	@Override
	public boolean connected() {
		return store.connected();
	}

	@Override
	public ChatClient.Builder chatClientBuilder() {
		if (!store.connected()) {
			throw new BookingException("No LLM provider connected: connect one in the Settings panel",
					HttpURLConnection.HTTP_BAD_REQUEST);
		}
		LlmProviderStore.ProviderSnapshot snapshot = store.current()
				.orElseThrow(() -> new BookingException("No LLM provider connected",
						HttpURLConnection.HTTP_BAD_REQUEST));
		CachedClient ready = cached;
		if (ready == null || !ready.matches(snapshot)) {
			ready = new CachedClient(snapshot, build(snapshot));
			cached = ready;
		}
		return ready.client().clone();
	}

	private ChatClient.Builder build(LlmProviderStore.ProviderSnapshot snapshot) {
		// Normalize array-shaped `content` from providers (e.g. Mistral leanstral) before Spring AI
		// 1.0.0 parses it, on both the blocking RestClient and the streaming WebClient.
		org.springframework.web.client.RestClient.Builder rest = org.springframework.web.client.RestClient.builder()
				.requestInterceptor(OpenAiContentNormalizers.restInterceptor());
		org.springframework.web.reactive.function.client.WebClient.Builder web = org.springframework.web.reactive.function.client.WebClient
				.builder()
				.filter(OpenAiContentNormalizers.webFilter());
		org.springframework.ai.openai.api.OpenAiApi api =
				org.springframework.ai.openai.api.OpenAiApi.builder()
						.baseUrl(snapshot.baseUrl())
						.apiKey(snapshot.apiKey())
						.restClientBuilder(rest)
						.webClientBuilder(web)
						.build();
		if (!StringUtils.hasText(snapshot.model())) {
			throw new BookingException("The connected provider has no model selected");
		}
		OpenAiChatModel model = OpenAiChatModel.builder()
				.openAiApi(api)
				.defaultOptions(OpenAiChatOptions.builder()
						.model(snapshot.model())
						.temperature(temperature)
						.maxTokens(maxTokens)
						.build())
				.build();
		return ChatClient.builder(model);
	}

	/** Value of the cache key: kind, key and model fully identify a built client. */
	private record CachedClient(LlmProviderStore.ProviderSnapshot snapshot, ChatClient.Builder client) {

		boolean matches(LlmProviderStore.ProviderSnapshot other) {
			return snapshot.kind() == other.kind() && snapshot.baseUrl().equals(other.baseUrl())
					&& snapshot.apiKey().equals(other.apiKey())
					&& snapshot.model().equals(other.model());
		}
	}
}
