package com.panic.core.agent.runtime;

import com.panic.core.agent.spi.ToolSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of tool sources that actually exist in the app. YAML declarations only reference ids;
 * the Java object (and {@code @Tool} reflection) stays in code, where it belongs.
 * <p>
 * Filled at startup from every {@link ToolSource} bean (the typical case) plus the sources of every
 * {@link AgentToolSources} bean, so an agent module can contribute several sources with one bean.
 */
public final class AgentToolSources {

	private final Map<String, ToolSource> local = new LinkedHashMap<>();

	public AgentToolSources local(ToolSource source) {
		local.put(source.id(), source);
		return this;
	}

	public AgentToolSources local(AgentToolSources others) {
		others.local.forEach(local::put);
		return this;
	}

	public Optional<ToolSource> local(String id) {
		return Optional.ofNullable(local.get(id));
	}
}
