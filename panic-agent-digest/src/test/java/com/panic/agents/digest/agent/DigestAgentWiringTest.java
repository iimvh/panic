package com.panic.agents.digest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.core.agent.spi.AgentDefinition;
import org.junit.jupiter.api.Test;

/**
 * The digest agent is now a pure "summarize + present" agent: no built-in tools, no sub-agents, no
 * persistence — its facts come from the {@code web-search} common tool granted through the hub.
 */
class DigestAgentWiringTest {

	@Test
	void digestAgentHasNoOwnToolsOrSubAgentsAndUsesWebSearchFromTheHub() {
		AgentDefinition digest = new DigestAgentConfig().digestAgent();

		assertThat(digest.id()).isEqualTo("digest");
		assertThat(digest.promptFile()).isEqualTo("agents/digest/system.md");
		assertThat(digest.toolSources()).isEmpty();
		assertThat(digest.subAgentIds()).isEmpty();
		assertThat(digest.hubToolIds()).containsExactly("web-search");
	}
}
