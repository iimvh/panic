package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.AgentDefinition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent tree, wired explicitly at startup from the {@link AgentDefinition} beans.
 * <p>
 * Agents live in optional modules, so the same declaration can be valid with or without a given
 * agent JAR on the classpath. A sub-agent that has no definition (module absent or
 * {@code app.agents.<id>.enabled=false}) is treated as "not installed": the parent drops it from
 * tools + prompt and it shows up in {@link #missingSubAgents(String)}. Every structurally invalid
 * declaration (cycle, duplicate id, self-reference, duplicate sub-agent) still fails fast.
 */
public final class AgentRegistry {

	private final Map<String, AgentDefinition> agents;
	private final Set<String> declaredIds;
	private final String rootId;

	public AgentRegistry(List<AgentDefinition> definitions, String rootId) {
		this(definitions, rootId, Set.of());
	}

	/**
	 * @param declaredIds id of every agent declared in configuration (including disabled ones).
	 *                    This separates "disabled sub-agent" from "misspelled sub-agent id".
	 */
	public AgentRegistry(List<AgentDefinition> definitions, String rootId, Set<String> declaredIds) {
		if (rootId == null || rootId.isBlank()) {
			throw new IllegalStateException("No root agent configured (app.agents.root)");
		}
		this.rootId = rootId;
		Map<String, AgentDefinition> byId = new LinkedHashMap<>();
		for (AgentDefinition definition : definitions) {
			AgentDefinition previous = byId.putIfAbsent(definition.id(), definition);
			if (previous != null) {
				throw new IllegalStateException("Duplicate agent id '" + definition.id() + "'");
			}
		}
		this.agents = Map.copyOf(byId);
		java.util.Set<String> declared = new java.util.LinkedHashSet<>(byId.keySet());
		declared.addAll(declaredIds);
		declared.removeIf(java.util.Objects::isNull);
		this.declaredIds = Set.copyOf(declared);
		// A misspelled root is a config error and must fail fast; a deliberately disabled root still starts.
		if (!this.agents.containsKey(rootId) && !declared.contains(rootId)) {
			throw new IllegalStateException("app.agents.root='" + rootId + "' does not exist (valid ids: "
					+ String.join(", ", declared) + ")");
		}
		validate();
	}

	private void validate() {
		for (AgentDefinition agent : agents.values()) {
			detectDuplicateSubAgents(agent);
			for (String skill : agent.skillIds()) {
				if (skill == null || skill.isBlank()) {
					throw new IllegalStateException("agent '" + agent.id() + "' declares a blank skill id");
				}
			}
		}
		detectCycles();
		reachableFromRoot();
	}

	private void detectDuplicateSubAgents(AgentDefinition agent) {
		Set<String> seen = new LinkedHashSet<>();
		for (String sub : agent.subAgentIds()) {
			if (!seen.add(sub)) {
				throw new IllegalStateException(
						"agent '" + agent.id() + "' declares subAgent '" + sub + "' more than once");
			}
		}
	}

	private void detectCycles() {
		Map<String, Visit> state = new LinkedHashMap<>();
		for (String id : agents.keySet()) {
			walk(id, state, new ArrayDeque<>());
		}
	}

	private void walk(String id, Map<String, Visit> state, Deque<String> path) {
		Visit current = state.get(id);
		if (current == Visit.DONE) {
			return;
		}
		if (current == Visit.VISITING) {
			List<String> cycle = new ArrayList<>(path);
			cycle = cycle.subList(cycle.indexOf(id), cycle.size());
			cycle.add(id);
			throw new IllegalStateException("Agent cycle: " + String.join(" -> ", cycle));
		}
		state.put(id, Visit.VISITING);
		path.addLast(id);
		for (String sub : agents.get(id).subAgentIds()) {
			if (agents.containsKey(sub)) {
				walk(sub, state, path);
			}
		}
		path.removeLast();
		state.put(id, Visit.DONE);
	}

	private Set<String> reachableFromRoot() {
		Set<String> reachable = new LinkedHashSet<>();
		if (!agents.containsKey(rootId)) {
			// Disabled root: the app still starts and chat reports a clear error when invoked.
			return reachable;
		}
		Deque<String> queue = new ArrayDeque<>();
		queue.add(rootId);
		while (!queue.isEmpty()) {
			String id = queue.poll();
			if (!reachable.add(id)) {
				continue;
			}
			for (String sub : agents.get(id).subAgentIds()) {
				if (!agents.containsKey(sub)) {
					// Declared-but-disabled OR not installed at all: the parent drops it from
					// tools/prompt. Agents live in optional modules, so a missing one is not an error.
					continue;
				}
				queue.add(sub);
			}
		}
		return reachable;
	}

	public String rootId() {
		return rootId;
	}

	public Optional<AgentDefinition> find(String id) {
		return Optional.ofNullable(agents.get(id));
	}

	public AgentDefinition require(String id) {
		return find(id).orElseThrow(() -> new IllegalStateException("Agent '" + id + "' is not installed"));
	}

	public AgentDefinition root() {
		return find(rootId).orElseThrow(() -> new com.panic.exception.BookingException(
				"Root agent '" + rootId + "' is disabled (app.agents." + rootId + ".enabled=false)"));
	}

	public boolean isRootEnabled() {
		return agents.containsKey(rootId);
	}

	/** Agents reachable from root (skipping disabled branches). */
	public Set<String> reachable() {
		Set<String> result = new LinkedHashSet<>();
		collect(rootId, result);
		return result;
	}

	private void collect(String id, Set<String> acc) {
		AgentDefinition agent = agents.get(id);
		if (agent == null || !acc.add(id)) {
			return;
		}
		for (String sub : agent.subAgentIds()) {
			collect(sub, acc);
		}
	}

	/** Enabled sub-agents (declared and not disabled). */
	public List<AgentDefinition> visibleSubAgents(String parentId) {
		AgentDefinition parent = agents.get(parentId);
		if (parent == null) {
			return List.of();
		}
		return parent.subAgentIds().stream()
				.map(agents::get)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	/** Disabled sub-agent ids (declared via config but with no definition) — surfaced by the endpoint. */
	public List<String> disabledSubAgents(String parentId) {
		AgentDefinition parent = agents.get(parentId);
		if (parent == null) {
			return List.of();
		}
		return parent.subAgentIds().stream()
				.filter(sub -> !agents.containsKey(sub) && declaredIds.contains(sub))
				.toList();
	}

	/**
	 * Sub-agent ids declared by the parent but with no definition and no config entry — i.e. the
	 * agent's module is not on the classpath. Surfaced by the endpoint so a missing JAR is visible.
	 */
	public List<String> missingSubAgents(String parentId) {
		AgentDefinition parent = agents.get(parentId);
		if (parent == null) {
			return List.of();
		}
		return parent.subAgentIds().stream()
				.filter(sub -> !agents.containsKey(sub) && !declaredIds.contains(sub))
				.toList();
	}

	/** Direct sub-agent ids of this agent (including disabled ones). */
	public List<String> declaredSubAgentIds(String parentId) {
		AgentDefinition parent = agents.get(parentId);
		return parent == null ? List.of() : parent.subAgentIds();
	}

	public List<AgentDefinition> all() {
		return List.copyOf(agents.values());
	}

	/** One-line summary for the startup log. */
	public String describe() {
		return agents.values().stream()
				.map(a -> a.id() + (a.subAgentIds().isEmpty() ? "" : " -> " + String.join(",", a.subAgentIds())))
				.collect(Collectors.joining("; "));
	}

	private enum Visit {
		VISITING,
		DONE
	}
}
