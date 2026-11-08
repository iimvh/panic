package com.panic.core.agent.spi;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * A common tool contributed by the tool-plugin hub and available to any agent that is explicitly
 * granted it ({@code hubTools: [<id>]} in {@code agent.yaml}). This is the second kind of tool an
 * agent can hold, beside its own {@code toolSources} (built-in tools).
 * <p>
 * The hub is a separate axis from built-in tools on purpose: a common capability (web search,
 * fetch, calculator...) is authored once and granted per agent, instead of being copied into every
 * agent module. Granted plugin tools are prefixed {@code <agentId>__} by the runtime, exactly like
 * built-in tools, so names never clash across agents.
 */
public interface ToolPlugin {

	/** Stable id referenced by {@code hubTools} (e.g. {@code web-search}). */
	String id();

	/** Human/model-facing description of what the tool does (used in the hub listing + logs). */
	String description();

	/** The callbacks this plugin contributes. Names are raw; the runtime prefixes them. */
	List<ToolCallback> toolCallbacks();

	/** False when the plugin cannot run in this environment (e.g. missing configuration). */
	default boolean available() {
		return true;
	}
}
