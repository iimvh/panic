package com.meetingroom.agents.coordinator;

/**
 * Constants for the "coordinator" agent.
 * <p>
 * This agent has no config class: it only has sub-agents and no tools of its own. Metadata lives
 * in {@code resources/agents/coordinator/}; the prompt renders {@code {subAgents}} at runtime.
 */
public final class CoordinatorAgentConfig {

	public static final String ID = "coordinator";

	private CoordinatorAgentConfig() {
	}
}
