package com.panic.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.panic.core.agent.spi.AgentDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AgentDefinitionLoaderTest {

	private static ToolPluginHub hubWithWebSearch() {
		return new ToolPluginHub(List.of(ToolPluginStub.of("web-search")));
	}

	@Test
	void yamlOnlyAgentResolvesItsToolRefsAndHubGrants() throws Exception {
		AgentToolSources sources = new AgentToolSources().local(LocalToolSourceStub.of("stub-tools"));

		AgentDefinition definition = AgentDefinitionLoader.load(
				new ClassPathResource("agents/test-harness/agent.yaml"), sources, hubWithWebSearch());

		assertThat(definition.id()).isEqualTo("test-harness");
		assertThat(definition.promptFile()).isEqualTo("agents/test-harness/system.md");
		assertThat(definition.toolSources()).extracting("id").containsExactly("stub-tools");
		assertThat(definition.hubToolIds()).containsExactly("web-search");
		assertThat(definition.subAgentIds()).containsExactly("ghost");
	}

	@Test
	void unknownHubToolFailsFast() {
		AgentToolSources sources = new AgentToolSources().local(LocalToolSourceStub.of("stub-tools"));

		assertThatThrownBy(() -> AgentDefinitionLoader.load(
				new ClassPathResource("agents/test-harness/agent.yaml"), sources, ToolPluginHub.empty()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("web-search");
	}

	@Test
	void beanAndYamlAreMergedBeanWins() throws Exception {
		AgentToolSources sources = new AgentToolSources().local(LocalToolSourceStub.of("stub-tools"));
		AgentDefinition fromBean = AgentDefinition.builder("test-harness")
				.description("from the module bean")
				.promptFile("agents/test-harness/system.md")
				.subAgentIds("ghost")
				.build();

		List<AgentDefinition> merged = AgentDefinitionLoader.loadAll(
				"classpath*:agents/test-harness/agent.yaml", sources, id -> true, List.of(fromBean),
				hubWithWebSearch());

		assertThat(merged).hasSize(1);
		assertThat(merged.get(0).description()).isEqualTo("from the module bean");
		assertThat(merged.get(0).toolSources()).hasSize(1);
		assertThat(merged.get(0).hubToolIds()).containsExactly("web-search");
	}
}
