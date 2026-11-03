package com.meetingroom.core.agent.runtime;

import com.meetingroom.agents.catalog.RoomCatalogAgentConfig;
import com.meetingroom.core.agent.spi.AgentDefinition;
import com.meetingroom.core.agent.spi.PromptRepository;
import com.meetingroom.core.agent.spi.ToolSource;
import com.meetingroom.core.agent.tool.McpToolSource;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the agent tree explicitly at startup. Invalid declarations (unknown id, cycle) make the
 * app fail fast in {@link AgentRegistry}; disabled agents disappear from the tree.
 * <p>
 * Agent metadata (description, prompt, skills, sub-agents, policy, tool refs) is loaded from
 * {@code agents/&#42;/agent.yaml}; code only registers real tool sources in {@link AgentToolSources}.
 * Disabled agents ({@code app.agents.<id>.enabled=false}) are dropped here, so a parent only
 * sees enabled sub-agents.
 */
@Configuration
public class AgentRuntimeConfig {

	/**
	 * Registers real tool sources in the lookup table used by YAML declarations. A disabled agent's
	 * {@code ToolSource} bean does not exist; a YAML declaration still pointing at it fails fast in
	 * the loader (that agent has already been filtered out of the registry, so it is harmless).
	 */
	@Bean
	public AgentToolSources agentToolSources(ObjectProvider<ToolSource> toolSources,
			ObjectProvider<ToolCallbackProvider> mcpProviders) {
		AgentToolSources sources = new AgentToolSources();
		toolSources.orderedStream().forEach(sources::local);
		ToolCallbackProvider provider = mcpProviders.getIfAvailable();
		sources.mcp(RoomCatalogAgentConfig.MCP_SERVER,
				provider == null ? List.of() : List.of(provider.getToolCallbacks()));
		return sources;
	}
	@Bean
	public AgentRegistry agentRegistry(AgentToolSources sources, Environment environment) {
		AgentCatalogProperties catalog = catalog(environment);
		List<AgentDefinition> definitions = AgentDefinitionLoader.loadAll(sources, catalog::isEnabled);
		return new AgentRegistry(definitions, catalog.root(), new LinkedHashSet<>(catalog.declaredIds()));
	}

	@Bean
	public AgentPromptRenderer agentPromptRenderer(PromptRepository prompts) {
		return new AgentPromptRenderer(prompts);
	}

	/**
	 * Binds {@code app.agents.*} into a flat map: {@code root} is a single value, each agent is a
	 * {@code {enabled: ...}} map. Binding directly into a record is avoided because the two types conflict.
	 */
	static AgentCatalogProperties catalog(Environment environment) {
		Binder binder = new Binder(ConfigurationPropertySources.get(environment));
		Map<String, Object> raw = binder.bind("app.agents", Bindable.mapOf(String.class, Object.class))
				.orElseGet(LinkedHashMap::new);
		Map<String, Boolean> flags = new LinkedHashMap<>();
		raw.forEach((id, value) -> {
			if ("root".equals(id)) {
				return;
			}
			if (value instanceof Map<?, ?> map) {
				Object enabled = map.get("enabled");
				flags.put(id, enabled == null || Boolean.parseBoolean(String.valueOf(enabled)));
			}
		});
		Object root = raw.get("root");
		return new AgentCatalogProperties(root == null ? null : String.valueOf(root), flags);
	}
}
