package com.panic.core.agent.chat.provider;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Hands every LLM caller a chat client built from the store's currently active
 * provider: the key and model live in the database, so a Settings-panel change
 * applies from the next request on. Nothing here reads environment variables.
 */
public interface LlmProviderChatFactory {

	/** True once a provider (key + model) is configured and served to callers. */
	boolean connected();

	/** A fresh client builder for one run; throws when no provider is connected. */
	ChatClient.Builder chatClientBuilder();

	/** Test/service fixture: a factory pinned to one already-built client builder. */
	static LlmProviderChatFactory fixed(ChatClient.Builder builder) {
		return new LlmProviderChatFactory() {
			@Override
			public boolean connected() {
				return true;
			}

			@Override
			// Cloning mirrors the runtime factory: each caller configures its own defaults.
			public ChatClient.Builder chatClientBuilder() {
				return builder.clone();
			}
		};
	}
}
