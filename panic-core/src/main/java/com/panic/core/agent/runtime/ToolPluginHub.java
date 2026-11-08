package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.ToolPlugin;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of common tool plugins (the tool-plugin hub). Every {@link ToolPlugin} bean is
 * collected here at startup; an agent is granted a plugin by id through {@code hubTools} in its
 * YAML declaration, resolved against this hub.
 * <p>
 * Kept separate from {@link AgentToolSources} so "built-in tools of an agent" and "common tools
 * from the hub" stay two distinct concepts, each granted explicitly.
 */
public final class ToolPluginHub {

	private final Map<String, ToolPlugin> plugins = new LinkedHashMap<>();

	public ToolPluginHub(Collection<? extends ToolPlugin> plugins) {
		if (plugins != null) {
			for (ToolPlugin plugin : plugins) {
				this.plugins.put(plugin.id(), plugin);
			}
		}
	}

	public static ToolPluginHub empty() {
		return new ToolPluginHub(java.util.List.of());
	}

	public Optional<ToolPlugin> find(String id) {
		return Optional.ofNullable(plugins.get(id));
	}

	/** Ids of every registered plugin, in registration order. */
	public Collection<String> ids() {
		return java.util.List.copyOf(plugins.keySet());
	}
}
