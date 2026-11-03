package com.meetingroom.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meetingroom.core.agent.spi.AgentDefinition;
import com.meetingroom.core.agent.spi.ProviderKind;
import com.meetingroom.core.agent.tool.LocalToolSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.Resource;

class AgentDefinitionLoaderTest {

	static final class StubTools {

		@Tool(name = "noop", description = "does nothing")
		public String noop() {
			return "";
		}
	}

	private final AgentToolSources sources = new AgentToolSources()
			.local(LocalToolSource.of("booking-tools", new StubTools()))
			.local(LocalToolSource.of("search-tools", new StubTools()))
			.local(LocalToolSource.of("cancellation-tools", new StubTools()));

	private static Resource yaml(String text) {
		return new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "agent.yaml");
	}

	@Test
	void loadsAllBundledAgentsFromYaml() {
		List<AgentDefinition> definitions = AgentDefinitionLoader.loadAll(sources);

		assertThat(definitions).extracting(AgentDefinition::id)
				.containsExactlyInAnyOrder("coordinator", "search", "booking", "cancellation", "roomCatalog");
		AgentDefinition booking = definitions.stream()
				.filter(d -> "booking".equals(d.id())).findFirst().orElseThrow();
		assertThat(booking.description()).isNotBlank();
		assertThat(booking.promptLocation()).contains("agents/booking/system.md");
		assertThat(booking.skillIds()).containsExactly("booking-policy");
		assertThat(booking.toolSources()).extracting(source -> source.id()).containsExactly("booking-tools");
		assertThat(booking.toolSources().get(0).provider()).isEqualTo(ProviderKind.LOCAL);
	}

	@Test
	void defaultPromptPathIsAgentsIdSystemMd() {
		AgentDefinition definition = AgentDefinitionLoader.load(
				yaml("id: solo\ndescription: d\n"), sources);

		assertThat(definition.promptLocation()).contains("agents/solo/system.md");
		assertThat(definition.policy().maxDepth()).isEqualTo(com.meetingroom.core.agent.spi.AgentPolicy.DEFAULT.maxDepth());
	}

	@Test
	void promptRelativeToAgentDirectoryIsNormalized() {
		AgentDefinition definition = AgentDefinitionLoader.load(
				yaml("id: solo\nprompt: prompts/main.md\n"), sources);

		assertThat(definition.promptLocation()).contains("agents/solo/prompts/main.md");
	}

	@Test
	void mcpToolReferenceBecomesMcpSource() {
		AgentDefinition definition = AgentDefinitionLoader.load(
				yaml("id: cat\ntools: [mcp:room-catalog]\n"), sources);

		assertThat(definition.toolSources()).singleElement()
				.satisfies(source -> {
					assertThat(source.id()).isEqualTo("room-catalog");
					assertThat(source.provider()).isEqualTo(ProviderKind.MCP);
				});
	}

	@Test
	void policyOverridesFromYaml() {
		AgentDefinition definition = AgentDefinitionLoader.load(
				yaml("id: solo\npolicy: { maxDepth: 2, timeoutMs: 500, maxToolCalls: 3 }\n"), sources);

		assertThat(definition.policy().maxDepth()).isEqualTo(2);
		assertThat(definition.policy().timeoutMs()).isEqualTo(500);
		assertThat(definition.policy().maxToolCalls()).isEqualTo(3);
	}

	@Test
	void unknownLocalToolSourceFailsFast() {
		assertThatThrownBy(() -> AgentDefinitionLoader.load(
				yaml("id: solo\ntools: [local:missing]\n"), sources))
				.hasMessageContaining("which is not registered");
	}

	@Test
	void missingIdFailsFast() {
		assertThatThrownBy(() -> AgentDefinitionLoader.load(yaml("description: d\n"), sources))
				.hasMessageContaining("has no id");
	}

	@Test
	void bundledDefinitionsBuildAValidTree() {
		AgentRegistry registry = new AgentRegistry(AgentDefinitionLoader.loadAll(sources), "coordinator",
				Set.of("coordinator", "search", "booking", "cancellation", "roomCatalog"));

		assertThat(registry.visibleSubAgents("coordinator")).extracting(AgentDefinition::id)
				.containsExactly("search", "booking", "cancellation", "roomCatalog");
	}
}
