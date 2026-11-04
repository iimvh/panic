package com.panic.core.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.core.agent.spi.AgentDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AgentDefinitionLoaderTest {

	@Test
	void yamlOnlyAgentResolvesItsToolRefs() throws Exception {
		AgentToolSources sources = new AgentToolSources().local(LocalToolSourceStub.of("stub-tools"));

		AgentDefinition definition = AgentDefinitionLoader.load(
				new ClassPathResource("agents/test-harness/agent.yaml"), sources);

		assertThat(definition.id()).isEqualTo("test-harness");
		assertThat(definition.promptFile()).isEqualTo("agents/test-harness/system.md");
		assertThat(definition.toolSources()).extracting("id").containsExactly("stub-tools");
		assertThat(definition.subAgentIds()).containsExactly("ghost");
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
				"classpath*:agents/test-harness/agent.yaml", sources, id -> true, List.of(fromBean));

		assertThat(merged).hasSize(1);
		assertThat(merged.get(0).description()).isEqualTo("from the module bean");
		assertThat(merged.get(0).toolSources()).hasSize(1);
	}
}
