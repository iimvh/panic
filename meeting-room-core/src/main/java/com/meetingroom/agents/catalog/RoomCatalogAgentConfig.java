package com.meetingroom.agents.catalog;

/**
 * Constants for the "roomCatalog" agent.
 * <p>
 * This agent has no config class: metadata lives in {@code resources/agents/roomCatalog/} and its
 * tools come from the MCP server {@link #MCP_SERVER} (wired in {@code AgentRuntimeConfig}).
 */
public final class RoomCatalogAgentConfig {

	public static final String MCP_SERVER = "room-catalog";

	private RoomCatalogAgentConfig() {
	}
}
