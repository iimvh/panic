package com.panic.core.agent.spi;

import java.util.Optional;

/**
 * System-prompt source addressed by location (not content).
 * <p>
 * An agent only declares <em>where</em> its prompt lives; the content is loaded at render time so
 * prompts can be changed without touching code.
 */
public interface PromptRepository {

	/** Returns the prompt content at {@code location}, or empty if not found / not readable. */
	Optional<String> get(String location);
}
