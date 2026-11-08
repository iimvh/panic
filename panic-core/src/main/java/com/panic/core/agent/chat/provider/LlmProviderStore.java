package com.panic.core.agent.chat.provider;

import java.net.HttpURLConnection;
import java.time.LocalDateTime;
import java.util.Optional;

import com.panic.exception.BookingException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Source of truth for which LLM provider the workspace runs on: the active row of
 * {@code llm_provider_config}, configured exclusively through the Settings panel —
 * there is no environment fallback. {@link #current()} is read on every agent run,
 * so a saved model change applies from the next request on while a streaming run
 * keeps the settings it started with.
 */
@Service
public class LlmProviderStore {

	private final LlmProviderRepository repository;

	public LlmProviderStore(LlmProviderRepository repository) {
		this.repository = repository;
	}

	/** The active connection, or empty while the workspace has none configured. */
	@Transactional(readOnly = true)
	public Optional<ProviderSnapshot> current() {
		return repository.findFirstByActiveTrue().map(LlmProviderStore::snapshotOf);
	}

	/** Whether agents may call the LLM at all: one active row with key and model set. */
	@Transactional(readOnly = true)
	public boolean connected() {
		return current().isPresent();
	}

	/**
	 * Connects (or reconfigures) a provider: the kind's row is upserted with the new
	 * key/model and becomes the single active connection. Blank values are rejected
	 * before anything is written.
	 */
	@Transactional
	public ProviderSnapshot connect(LlmProviderKind kind, String apiKey, String model) {
		if (!StringUtils.hasText(apiKey)) {
			throw new BookingException("API key must not be blank");
		}
		if (!StringUtils.hasText(model)) {
			throw new BookingException("Model must not be blank");
		}
		LlmProviderConfig row = repository.findById(kind.externalId()).orElseGet(() -> {
			LlmProviderConfig created = new LlmProviderConfig();
			created.setProviderKind(kind.externalId());
			created.setCreatedAt(LocalDateTime.now());
			return created;
		});
		row.setApiKey(apiKey.strip());
		row.setModel(model.strip());
		row.setActive(true);
		row.setUpdatedAt(LocalDateTime.now());
		repository.save(row);
		// Single-active invariant: only one provider serves the agents at a time.
		repository.findAll().forEach(other -> {
			if (other.isActive() && !kind.externalId().equals(other.getProviderKind())) {
				other.setActive(false);
				other.setUpdatedAt(LocalDateTime.now());
				repository.save(other);
			}
		});
		return snapshotOf(row);
	}

	private static ProviderSnapshot snapshotOf(LlmProviderConfig row) {
		LlmProviderKind kind = LlmProviderKind.ofExternalId(row.getProviderKind());
		if (kind == null) {
			throw new BookingException("Unknown stored provider kind '" + row.getProviderKind() + "'",
					HttpURLConnection.HTTP_INTERNAL_ERROR);
		}
		return new ProviderSnapshot(kind, kind.baseUrl(), row.getApiKey(), row.getModel());
	}

	/** The runtime view of one connection: everything the chat client needs per run. */
	public record ProviderSnapshot(LlmProviderKind kind, String baseUrl, String apiKey, String model) {
	}
}
