package com.panic.core.agent.chat.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One connected LLM provider: its API key and the chat model selected for every run.
 * The row id is the provider kind, so each backend owns at most one configured
 * connection and the {@link #active} flag marks the single one serving the agents.
 */
@Entity
@Table(name = "llm_provider_config")
@Getter
@Setter
public class LlmProviderConfig {

	/** One configured provider row per kind: "openrouter" today, more backends later. */
	@Id
	@Column(name = "provider_kind", length = 32)
	private String providerKind;

	/** Secret sent as the OpenAI-compatible bearer credential; never returned to clients. */
	@Column(nullable = false, length = 256)
	private String apiKey;

	/** Full model id chosen in the workspace composer; applied from the next request on. */
	@Column(nullable = false, length = 160)
	private String model;

	/** Marks the connection the agents currently run on (single-active invariant). */
	@Column(nullable = false)
	private boolean active;

	@Column(nullable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	@Column(nullable = false)
	private LocalDateTime updatedAt = LocalDateTime.now();
}
