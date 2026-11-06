package com.panic.agents.digest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.panic.agents.digest.DigestProperties;
import com.panic.agents.digest.digest.DigestService;
import com.panic.agents.digest.feed.FeedSourceRepository;
import com.panic.core.agent.runtime.AgentDefinitionLoader;
import com.panic.core.agent.runtime.AgentToolSources;
import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.tool.LocalToolSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

/**
 * Agent wiring seam (reused pattern): each digest agent's YAML plus tool refs resolve, its prompt
 * file exists, and the tree declares the expected sub-agents — proving installation without a model.
 */
class DigestAgentWiringTest {

	private final DigestService digests = Mockito.mock(DigestService.class);
	private final AgentToolSources sources = new AgentToolSources()
			.local(LocalToolSource.of(DigestAgentConfig.DIGEST_TOOLS,
					new DigestTools(digests, new DigestProperties())))
			.local(LocalToolSource.of(DigestAgentConfig.NEWS_SCOUT_TOOLS,
					new NewsScoutTools(digests, Mockito.mock(FeedSourceRepository.class))))
			.local(LocalToolSource.of(DigestAgentConfig.DIGEST_WRITER_TOOLS,
					new DigestWriterTools(digests)));

	@Test
	void digestAgentMountsBothSubAgentsAndItsToolSource() {
		// Production path: the module bean carries metadata + sub-agents, the YAML carries tool refs.
		AgentDefinition bean = new DigestAgentConfig().digestAgent();

		AgentDefinition digest = AgentDefinitionLoader.loadAll(
				"classpath*:agents/digest/agent.yaml", sources, id -> true, List.of(bean)).stream()
				.findFirst().orElseThrow();

		assertThat(digest.id()).isEqualTo(DigestAgentConfig.DIGEST);
		assertThat(digest.subAgentIds()).containsExactly(DigestAgentConfig.NEWS_SCOUT,
				DigestAgentConfig.DIGEST_WRITER);
		assertThat(digest.promptFile()).isEqualTo("agents/digest/system.md");
		assertThat(digest.toolSources()).extracting("id").containsExactly(DigestAgentConfig.DIGEST_TOOLS);
	}

	@Test
	void newsScoutAndDigestWriterAreLeavesWithTheirOwnToolSources() throws Exception {
		AgentDefinition scout = loadOne("agents/newsScout/agent.yaml");
		AgentDefinition writer = loadOne("agents/digestWriter/agent.yaml");

		assertThat(scout.isLeaf()).isTrue();
		assertThat(scout.toolSources()).extracting("id").containsExactly(DigestAgentConfig.NEWS_SCOUT_TOOLS);
		assertThat(writer.isLeaf()).isTrue();
		assertThat(writer.toolSources()).extracting("id").containsExactly(DigestAgentConfig.DIGEST_WRITER_TOOLS);
	}

	@Test
	void everyAgentHasAMandatorySystemPromptFile() {
		List<String> prompts = List.of("agents/digest/system.md", "agents/newsScout/system.md",
				"agents/digestWriter/system.md");

		prompts.forEach(prompt -> assertThat(new ClassPathResource(prompt).exists())
				.as(prompt + " must exist").isTrue());
	}

	private AgentDefinition loadOne(String location) {
		return AgentDefinitionLoader.loadAll(location, sources).stream().findFirst().orElseThrow();
	}

	@Test
	void digestToolsExposeTodayForDateArchiveAndGenerate() {
		List<String> names = LocalToolSource.of(DigestAgentConfig.DIGEST_TOOLS,
						new DigestTools(digests, new DigestProperties()))
				.toolCallbacks().stream().map(callback -> callback.getToolDefinition().name()).toList();

		assertThat(names).containsExactlyInAnyOrder("today", "forDate", "archive", "generate");
	}
}
