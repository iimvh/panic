package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.spi.PromptRepository;
import com.panic.core.agent.spi.ToolSource;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;

/**
 * Wires the agent tree explicitly at startup. Structurally invalid declarations (cycle, duplicate
 * id) make the app fail fast in {@link AgentRegistry}; agents that are not installed disappear from
 * the tree.
 * <p>
 * Agent metadata (description, prompt, skills, sub-agents, policy, tool refs) is loaded from
 * {@code agents/&#42;/agent.yaml} <b>on the whole classpath</b>, so bundling an agent = adding its
 * module/JAR. Code only registers real tool sources in {@link AgentToolSources}.
 * Agents absent from the classpath (module not on the classpath) or disabled
 * ({@code app.agents.<id>.enabled=false}) are dropped here, so a parent only sees enabled sub-agents.
 * <p>
 * This class is framework code: it must not reference any concrete bundled agent.
 */
@Configuration
public class AgentRuntimeConfig {

	private static final Logger log = LoggerFactory.getLogger(AgentRuntimeConfig.class);

	/**
	 * Registers real tool sources in the lookup table used by YAML declarations. A disabled agent's
	 * {@code ToolSource} bean does not exist; a YAML declaration still pointing at it fails fast in
	 * the loader (that agent has already been filtered out of the registry, so it is harmless).
	 */
	@Bean
	public AgentToolSources agentToolSources(ObjectProvider<ToolSource> toolSources,
			ObjectProvider<AgentToolSources> contributed) {
		AgentToolSources sources = new AgentToolSources();
		toolSources.orderedStream().forEach(sources::local);
		contributed.orderedStream().forEach(sources::local);
		return sources;
	}
	@Bean
	public AgentRegistry agentRegistry(@Qualifier("agentToolSources") AgentToolSources sources,
			ObjectProvider<AgentDefinition> beanDefinitions, Environment environment) {
		AgentCatalogProperties catalog = catalog(environment);
		List<AgentDefinition> definitions = AgentDefinitionLoader.loadAll(
				AgentDefinitionLoader.DEFAULT_LOCATION, sources, catalog::isEnabled, beanDefinitions.orderedStream().toList());
		AgentRegistry registry = new AgentRegistry(definitions, catalog.root(),
				new LinkedHashSet<>(catalog.declaredIds()));
		registry.all().forEach(agent -> {
			List<String> missing = registry.missingSubAgents(agent.id());
			if (!missing.isEmpty()) {
				log.warn("agent '{}' declares sub-agents {} whose module is not on the classpath "
						+ "(they are dropped from the tree; add the agent module to enable them)",
						agent.id(), missing);
			}
		});
		return registry;
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
