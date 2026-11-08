package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.AgentDefinition;
import com.panic.core.agent.spi.AgentPolicy;
import com.panic.core.agent.spi.ToolSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads agent declarations from {@code classpath*:agents/&#42;/agent.yaml}.
 * <p>
 * The file only holds metadata and references, never prompt or skill content:
 * <pre>
 * id: booking
 * description: ...
 * prompt: system.md                 # relative to agents/&lt;id&gt;/
 * skills: [booking-policy]
 * subAgents: []
 * policy: { maxDepth: 5, timeoutMs: 120000, maxToolCalls: 8 }
 * tools: [local:my-tools]           # local:&lt;toolSourceId&gt;
 * </pre>
 * The real tool source (a Java object) is registered by id in {@link AgentToolSources}. When
 * {@code tools} is omitted the agent has no tools — valid for an agent that only has sub-agents.
 */
public final class AgentDefinitionLoader {

	private static final Logger log = LoggerFactory.getLogger(AgentDefinitionLoader.class);
	static final String DEFAULT_LOCATION = "classpath*:agents/*/agent.yaml";

	private AgentDefinitionLoader() {
	}

	public static List<AgentDefinition> loadAll(AgentToolSources sources) {
		return loadAll(DEFAULT_LOCATION, sources, id -> true);
	}

	/**
	 * @param enabled filters by id <b>before</b> resolving tool refs — a disabled agent has no
	 *                tool source bean, so it must be dropped before resolution.
	 */
	public static List<AgentDefinition> loadAll(AgentToolSources sources, Predicate<String> enabled) {
		return loadAll(DEFAULT_LOCATION, sources, enabled);
	}

	public static List<AgentDefinition> loadAll(String location, AgentToolSources sources) {
		return loadAll(location, sources, id -> true);
	}

	public static List<AgentDefinition> loadAll(String location, AgentToolSources sources,
			Predicate<String> enabled) {
		return loadAll(location, sources, enabled, List.of());
	}

	public static List<AgentDefinition> loadAll(String location, AgentToolSources sources,
			Predicate<String> enabled, List<AgentDefinition> existing) {
		return loadAll(location, sources, enabled, existing, ToolPluginHub.empty());
	}

	/**
	 * @param hub the tool-plugin hub; {@code hubTools} refs are validated against it so a typo or a
	 *            plugin whose module is missing fails fast at startup.
	 */
	public static List<AgentDefinition> loadAll(String location, AgentToolSources sources,
			Predicate<String> enabled, List<AgentDefinition> existing, ToolPluginHub hub) {
		Resource[] resources;
		try {
			resources = new PathMatchingResourcePatternResolver().getResources(location);
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot scan agents at '" + location + "': " + ex.getMessage(), ex);
		}
		if (resources.length == 0) {
			log.warn("No agent declarations found at '{}'", location);
		}
		List<AgentDefinition> result = new ArrayList<>();
		Map<String, Map<String, Object>> byId = new HashMap<>();
		Map<String, Map<String, Object>> srcById = new HashMap<>();
		for (Resource resource : resources) {
			Map<String, Object> raw = read(resource);
			byId.put(idOf(raw, resource), raw);
		}
		Set<String> fromBeans = existing.stream().map(AgentDefinition::id)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		for (Map.Entry<String, Map<String, Object>> entry : byId.entrySet()) {
			String id = entry.getKey();
			srcById.put(id, entry.getValue());
			if (!enabled.test(id) || fromBeans.contains(id)) {
				continue;
			}
			result.add(build(id, entry.getValue(), sources, hub));
		}
		for (AgentDefinition definition : existing) {
			if (!enabled.test(definition.id())) {
				continue;
			}
			// The module's own bean is authoritative for metadata; a YAML declaration of the same id
			// only adds what the bean omits (tool refs resolve here, not in the module).
			Map<String, Object> raw = srcById.get(definition.id());
			result.add(raw == null ? definition : withTools(definition, raw, sources, hub));
		}
		return result;
	}

	/** Loads a single agent from a resource; used by tests. */
	static AgentDefinition load(Resource resource, AgentToolSources sources) {
		return load(resource, sources, ToolPluginHub.empty());
	}

	static AgentDefinition load(Resource resource, AgentToolSources sources, ToolPluginHub hub) {
		Map<String, Object> raw = read(resource);
		return build(idOf(raw, resource), raw, sources, hub);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> read(Resource resource) {
		try (InputStream input = resource.getInputStream()) {
			Object parsed = new Yaml().load(input);
			return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
		} catch (IOException | RuntimeException ex) {
			throw new IllegalStateException("Reading agent declaration '" + resource.getDescription() + "' failed: "
					+ ex.getMessage(), ex);
		}
	}

	private static String idOf(Map<String, Object> raw, Resource resource) {
		String id = string(raw.get("id"));
		if (id == null || id.isBlank()) {
			throw new IllegalStateException("Agent declaration '" + resource.getDescription() + "' has no id");
		}
		return id;
	}

	private static AgentDefinition build(String id, Map<String, Object> raw, AgentToolSources sources,
			ToolPluginHub hub) {
		AgentDefinition.Builder builder = AgentDefinition.builder(id)
				.description(string(raw.get("description")))
				.promptFile(promptPath(id, string(raw.get("prompt"))))
				.skillIds(strings(raw.get("skills")))
				.subAgentIds(strings(raw.get("subAgents")))
				.policy(policy(raw.get("policy")));
		for (String ref : strings(raw.get("tools"))) {
			builder.addToolSource(resolveTool(ref, sources, id));
		}
		for (String pluginId : strings(raw.get("hubTools"))) {
			builder.addHubToolId(resolveHubTool(pluginId, hub, id));
		}
		return builder.build();
	}

	/** Bean definition + the tool refs + hub grants declared in the same agent's YAML. */
	private static AgentDefinition withTools(AgentDefinition definition, Map<String, Object> raw,
			AgentToolSources sources, ToolPluginHub hub) {
		AgentDefinition.Builder builder = AgentDefinition.builder(definition.id())
				.description(definition.description())
				.promptFile(definition.promptFile())
				.systemPromptTemplate(definition.inlinePrompt())
				.skillIds(definition.skillIds())
				.subAgentIds(definition.subAgentIds())
				.policy(definition.policy());
		definition.toolSources().forEach(builder::addToolSource);
		definition.hubToolIds().forEach(builder::addHubToolId);
		for (String ref : strings(raw.get("tools"))) {
			builder.addToolSource(resolveTool(ref, sources, definition.id()));
		}
		for (String pluginId : strings(raw.get("hubTools"))) {
			builder.addHubToolId(resolveHubTool(pluginId, hub, definition.id()));
		}
		return builder.build();
	}

	/** The prompt in YAML is relative to {@code agents/<id>/}; defaults to {@code system.md}. */
	private static String promptPath(String agentId, String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return "agents/" + agentId + "/system.md";
		}
		return prompt.startsWith("agents/") ? prompt : "agents/" + agentId + "/" + prompt;
	}

	private static ToolSource resolveTool(String ref, AgentToolSources sources, String agentId) {
		int colon = ref.indexOf(':');
		if (colon <= 0 || colon == ref.length() - 1) {
			throw new IllegalStateException("agent '" + agentId + "' declares tool '" + ref
					+ "' in the wrong format (expected local:<id>)");
		}
		String kind = ref.substring(0, colon).trim();
		String value = ref.substring(colon + 1).trim();
		return switch (kind) {
			case "local" -> sources.local(value)
					.orElseThrow(() -> new IllegalStateException("agent '" + agentId
							+ "' references local tool source '" + value + "' which is not registered"));
			default -> throw new IllegalStateException("agent '" + agentId + "' declares tool '" + ref
					+ "' with an unsupported kind: " + kind);
		};
	}

	/**
	 * Resolves a {@code hubTools} grant against the tool-plugin hub. An unknown id (typo, or the
	 * plugin's module not on the classpath) fails fast at startup, like an unknown tool source.
	 */
	private static String resolveHubTool(String pluginId, ToolPluginHub hub, String agentId) {
		if (pluginId == null || pluginId.isBlank()) {
			throw new IllegalStateException("agent '" + agentId + "' declares a blank hub tool id");
		}
		hub.find(pluginId).orElseThrow(() -> new IllegalStateException("agent '" + agentId
				+ "' references hub tool '" + pluginId + "' which is not registered (available: "
				+ String.join(", ", hub.ids()) + ")"));
		return pluginId;
	}

	private static AgentPolicy policy(Object raw) {
		if (raw instanceof Map<?, ?> map) {
			AgentPolicy fallback = AgentPolicy.DEFAULT;
			return new AgentPolicy(
					integer(map.get("maxDepth"), fallback.maxDepth()),
					longValue(map.get("timeoutMs"), fallback.timeoutMs()),
					integer(map.get("maxToolCalls"), fallback.maxToolCalls()));
		}
		return AgentPolicy.DEFAULT;
	}

	private static int integer(Object value, int fallback) {
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static long longValue(Object value, long fallback) {
		return value instanceof Number number ? number.longValue() : fallback;
	}

	private static String string(Object value) {
		return value == null ? null : String.valueOf(value).trim();
	}

	private static List<String> strings(Object value) {
		if (value == null) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		if (value instanceof List<?> list) {
			for (Object item : list) {
				String text = string(item);
				if (text != null && !text.isBlank()) {
					result.add(text);
				}
			}
			return result;
		}
		String text = string(value);
		if (text == null || text.isBlank()) {
			return List.of();
		}
		for (String item : text.split(",")) {
			if (!item.isBlank()) {
				result.add(item.trim());
			}
		}
		return result;
	}
}
