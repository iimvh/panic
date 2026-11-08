package com.panic.agents.digest.agent;

import com.panic.core.agent.spi.AgentDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code digest} agent: a news editor. It owns no tools, no sub-agents and no persistence — its
 * system prompt teaches how to worth together and present a Vietnamese morning digest, and it gets
 * its facts live from the {@code web-search} common tool of the tool-plugin hub
 * ({@code hubTools: [web-search]} in {@code agent.yaml}). Disable with
 * {@code app.agents.digest.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(name = "app.agents.digest.enabled", havingValue = "true", matchIfMissing = true)
public class DigestAgentConfig {

	public static final String DIGEST = "digest";

	@Bean
	public AgentDefinition digestAgent() {
		return AgentDefinition.builder(DIGEST)
				.description("Digest editor: summarizes and presents daily tech news from "
						+ "web-search results and replies in the user's language.")
				.promptFile("agents/digest/system.md")
				.hubToolIds("web-search")
				.build();
	}
}
