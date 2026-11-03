package com.meetingroom.core.agent.runtime;

import com.meetingroom.core.agent.spi.ToolSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;

/**
 * Registry of tool sources that actually exist in the app. YAML declarations only reference ids;
 * the Java object (and {@code @Tool} reflection) stays in code, where it belongs.
 */
public final class AgentToolSources {

	private final Map<String, ToolSource> local = new LinkedHashMap<>();
	private final Map<String, List<ToolCallback>> mcp = new LinkedHashMap<>();

	public AgentToolSources local(ToolSource source) {
		local.put(source.id(), source);
		return this;
	}

	public AgentToolSources mcp(String server, List<ToolCallback> callbacks) {
		mcp.put(server, callbacks == null ? List.of() : List.copyOf(callbacks));
		return this;
	}

	public Optional<ToolSource> local(String id) {
		return Optional.ofNullable(local.get(id));
	}

	public Optional<List<ToolCallback>> mcp(String server) {
		return Optional.ofNullable(mcp.get(server));
	}
}
