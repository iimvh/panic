package com.panic.core.agent.chat.provider;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmProviderRepository extends JpaRepository<LlmProviderConfig, String> {

	/** The connection currently serving the agents; the single active row. */
	Optional<LlmProviderConfig> findFirstByActiveTrue();
}
