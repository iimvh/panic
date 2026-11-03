package com.meetingroom.core.agent.spi;

import java.util.List;
import java.util.Optional;

/**
 * View of an agent plugin. All real metadata lives in {@link AgentDefinition}; this interface
 * only lets code treat an agent as one uniform entity (coordinators and leaf agents share a
 * single type, recursively, without limits).
 */
public interface Agent {

	String id();

	String description();

	/** Prompt location (e.g. {@code agents/booking/system.md}); empty if the agent uses an inline prompt. */
	Optional<String> promptLocation();

	/** Inline prompt; for tests only. Production leaves it empty and uses {@link #promptLocation()}. */
	String inlinePrompt();

	List<ToolSource> toolSources();

	List<String> subAgentIds();

	List<String> skillIds();

	AgentPolicy policy();

	default boolean isLeaf() {
		return subAgentIds().isEmpty();
	}
}
