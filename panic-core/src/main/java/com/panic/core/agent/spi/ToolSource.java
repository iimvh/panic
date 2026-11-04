package com.panic.core.agent.spi;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tool source of an agent. The core only knows about {@link ToolCallback}; whether a tool comes
 * from a local {@code @Tool} method or from an MCP server is an implementation detail.
 * <p>
 * Names returned here are raw; {@code AgentExecutor} prefixes them with {@code <agentId>__} to
 * avoid clashes between agents and between local and MCP tools.
 */
public interface ToolSource {

	String id();

	ProviderKind provider();

	List<ToolCallback> toolCallbacks();

	default boolean available() {
		return true;
	}
}
