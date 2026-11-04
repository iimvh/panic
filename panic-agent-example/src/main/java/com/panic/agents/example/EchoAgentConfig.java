package com.panic.agents.example;

import com.panic.core.agent.runtime.AgentToolSources;
import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.tool.LocalToolSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Example agent module: an {@code agent.yaml} on the classpath plus this bean is all it takes to
 * install an agent. No change to {@code panic-core} or {@code AgentRuntimeConfig}.
 * <p>
 * The parent ({@code coordinator}) mounts it by listing it in its {@code subAgents} declaration;
 * the module itself only supplies the definition and its tool source.
 */
@Configuration
@ConditionalOnProperty(name = "app.agents.echo.enabled", havingValue = "true", matchIfMissing = true)
public class EchoAgentConfig {

	public static final String ID = "echo";
	/** Tool source id referenced from {@code agent.yaml} as {@code local:echo-tools}. */
	public static final String TOOL_SOURCE_ID = "echo-tools";

	/**
	 * The definition bean is only about metadata + tool refs: the refs are resolved in
	 * {@code AgentDefinitionLoader} against the merged {@link AgentToolSources}, so this bean must
	 * not depend on the source being present.
	 */
	@Bean
	public AgentDefinition echoAgent() {
		return AgentDefinition.builder(ID)
				.description("Echoes a short text back; example of a plugged-in agent module.")
				.promptFile("agents/echo/system.md")
				.build();
	}

	/** Tool sources contributed by this module; merged into the runtime's lookup table at startup. */
	@Bean
	public AgentToolSources echoToolSources(EchoTools tools) {
		return new AgentToolSources().local(LocalToolSource.of(TOOL_SOURCE_ID, tools));
	}
}
