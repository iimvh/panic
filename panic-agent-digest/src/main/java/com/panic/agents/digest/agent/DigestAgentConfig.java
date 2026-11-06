package com.panic.agents.digest.agent;

import com.panic.agents.digest.DigestProperties;
import com.panic.agents.digest.digest.DigestService;
import com.panic.agents.digest.feed.FeedSourceRepository;
import com.panic.core.agent.runtime.AgentToolSources;
import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.tool.LocalToolSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mounts the three digest agents as standard self-describing plugins. Each is a definition bean plus
 * a YAML declaration ({@code agents/<id>/agent.yaml}) and a mandatory {@code system.md}; tools are
 * contributed as local tool sources and prefixed {@code <agentId>__<tool>} by the runtime.
 * <p>
 * {@code coordinator} mounts {@code digest}; {@code digest} owns {@code newsScout} and
 * {@code digestWriter}. Bans the feature by setting {@code app.agents.digest.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(name = "app.agents.digest.enabled", havingValue = "true", matchIfMissing = true)
public class DigestAgentConfig {

	public static final String DIGEST = "digest";
	public static final String NEWS_SCOUT = "newsScout";
	public static final String DIGEST_WRITER = "digestWriter";

	public static final String DIGEST_TOOLS = "digest-tools";
	public static final String NEWS_SCOUT_TOOLS = "news-scout-tools";
	public static final String DIGEST_WRITER_TOOLS = "digest-writer-tools";

	// --- digest (coordinator of the feature) ---

	@Bean
	public AgentDefinition digestAgent() {
		return AgentDefinition.builder(DIGEST)
				.description("Bản tin mỗi sáng: answers with today's or a past day's Vietnamese digest, "
						+ "and can trigger generation on demand.")
				.promptFile("agents/digest/system.md")
				.subAgentIds(NEWS_SCOUT, DIGEST_WRITER)
				.build();
	}

	@Bean
	public AgentToolSources digestToolSources(DigestTools tools) {
		return new AgentToolSources().local(LocalToolSource.of(DIGEST_TOOLS, tools));
	}

	@Bean
	public DigestTools digestTools(DigestService digests, DigestProperties properties) {
		return new DigestTools(digests, properties);
	}

	// --- newsScout (ingestion) ---

	@Bean
	public AgentDefinition newsScoutAgent() {
		return AgentDefinition.builder(NEWS_SCOUT)
				.description("Fetches Vietnamese news feeds and reports the configured sources.")
				.promptFile("agents/news-scout/system.md")
				.build();
	}

	@Bean
	public AgentToolSources newsScoutToolSources(NewsScoutTools tools) {
		return new AgentToolSources().local(LocalToolSource.of(NEWS_SCOUT_TOOLS, tools));
	}

	@Bean
	public NewsScoutTools newsScoutTools(DigestService digests, FeedSourceRepository sources) {
		return new NewsScoutTools(digests, sources);
	}

	// --- digestWriter (summarize + save) ---

	@Bean
	public AgentDefinition digestWriterAgent() {
		return AgentDefinition.builder(DIGEST_WRITER)
				.description("Selects and summarizes the day's items and persists the digest.")
				.promptFile("agents/digest-writer/system.md")
				.build();
	}

	@Bean
	public AgentToolSources digestWriterToolSources(DigestWriterTools tools) {
		return new AgentToolSources().local(LocalToolSource.of(DIGEST_WRITER_TOOLS, tools));
	}

	@Bean
	public DigestWriterTools digestWriterTools(DigestService digests) {
		return new DigestWriterTools(digests);
	}
}
