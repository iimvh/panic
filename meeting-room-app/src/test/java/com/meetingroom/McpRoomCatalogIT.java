package com.meetingroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingroom.core.agent.runtime.AgentRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * MCP end-to-end: requires meeting-room-mcp-demo running at MCP_CATALOG_URL (default :8081).
 * Enable with RUN_MCP_IT=true so CI without network/port still passes.
 */
@SpringBootTest
@ActiveProfiles("mcp")
@EnabledIfEnvironmentVariable(named = "RUN_MCP_IT", matches = "true")
class McpRoomCatalogIT {

	@Autowired
	private AgentRegistry registry;

	@Test
	void roomCatalogExposesMcpToolsPrefixedByAgent() {
		var catalog = registry.require("roomCatalog");
		List<String> tools = catalog.toolSources().stream()
				.flatMap(source -> source.toolCallbacks().stream())
				.map(cb -> cb.getToolDefinition().name())
				.toList();

		assertThat(tools).contains("listRooms", "roomDetail");
		assertThat(tools).allMatch(name -> name.startsWith("spring_ai_mcp_client_room_catalog_")
				|| !name.contains("__"));
	}
}
