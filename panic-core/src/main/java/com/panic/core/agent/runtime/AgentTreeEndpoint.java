package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.AgentDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * {@code GET /actuator/agents}: runtime agent tree (disabled agents removed).
 * Useful to verify plugin wiring without reading logs.
 */
@Component
@Endpoint(id = "agents")
public class AgentTreeEndpoint {

	private final AgentRegistry registry;

	public AgentTreeEndpoint(AgentRegistry registry) {
		this.registry = registry;
	}

	@ReadOperation
	public Map<String, Object> tree() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("root", registry.rootId());
		body.put("rootEnabled", registry.isRootEnabled());
		body.put("agents", registry.all().stream().map(this::describe).toList());
		if (registry.isRootEnabled()) {
			body.put("tree", node(registry.root(), Set.of()));
		}
		return body;
	}

	private Map<String, Object> describe(AgentDefinition agent) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", agent.id());
		map.put("description", agent.description());
		map.put("enabled", true);
		map.put("providers", agent.toolSources().stream().map(src -> src.provider().name()).distinct().toList());
		map.put("tools", agent.toolSources().stream()
				.flatMap(src -> src.toolCallbacks().stream())
				.map(cb -> ToolNamePrefix.apply(agent.id(), cb.getToolDefinition().name()))
				.toList());
		// Common tools granted from the tool-plugin hub (mounted at run time as <id>__<tool>).
		map.put("hubTools", agent.hubToolIds());
		map.put("subAgents", registry.visibleSubAgents(agent.id()).stream().map(AgentDefinition::id).toList());
		map.put("disabledSubAgents", registry.disabledSubAgents(agent.id()));
		map.put("missingSubAgents", registry.missingSubAgents(agent.id()));
		map.put("skills", agent.skillIds());
		map.put("policy", Map.of("maxDepth", agent.policy().maxDepth(),
				"timeoutMs", agent.policy().timeoutMs(), "maxToolCalls", agent.policy().maxToolCalls()));
		return map;
	}

	private Map<String, Object> node(AgentDefinition agent, Set<String> path) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", agent.id());
		List<Map<String, Object>> children = new ArrayList<>();
		Set<String> next = new java.util.LinkedHashSet<>(path);
		next.add(agent.id());
		for (AgentDefinition sub : registry.visibleSubAgents(agent.id())) {
			if (!next.contains(sub.id())) {
				children.add(node(sub, next));
			}
		}
		map.put("subAgents", children);
		return map;
	}
}
