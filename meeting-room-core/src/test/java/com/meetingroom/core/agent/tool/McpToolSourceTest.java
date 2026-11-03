package com.meetingroom.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.meetingroom.core.agent.spi.ProviderKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

class McpToolSourceTest {

	private static ToolCallback callback(String name) {
		ToolDefinition definition = DefaultToolDefinition.builder()
				.name(name)
				.description("d")
				.inputSchema("{\"type\":\"object\"}")
				.build();
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return name;
			}
		};
	}

	@Test
	void stripsSpringMcpInfrastructurePrefix() {
		McpToolSource source = new McpToolSource("room-catalog", List.of(
				callback("spring_ai_mcp_client_room_catalog_listRooms"),
				callback("spring_ai_mcp_client_room_catalog_roomDetail")));

		assertThat(source.toolCallbacks()).extracting(cb -> cb.getToolDefinition().name())
				.containsExactly("listRooms", "roomDetail");
		assertThat(source.provider()).isEqualTo(ProviderKind.MCP);
		assertThat(source.available()).isTrue();
	}

	@Test
	void keepsPlainToolNames() {
		McpToolSource source = new McpToolSource("room-catalog", List.of(callback("listRooms")));

		assertThat(source.toolCallbacks()).extracting(cb -> cb.getToolDefinition().name())
				.containsExactly("listRooms");
	}

	@Test
	void emptySourceIsUnavailableButDoesNotThrow() {
		McpToolSource source = new McpToolSource("room-catalog", List.of());

		assertThat(source.toolCallbacks()).isEmpty();
		assertThat(source.available()).isFalse();
	}

	@Test
	void renamedCallbackDelegatesInvocation() {
		McpToolSource source = new McpToolSource("room-catalog",
				List.of(callback("spring_ai_mcp_client_room_catalog_listRooms")));

		ToolCallback renamed = source.toolCallbacks().get(0);
		assertThat(renamed.getToolDefinition().name()).isEqualTo("listRooms");
		assertThat(renamed.call("{}")).isEqualTo("spring_ai_mcp_client_room_catalog_listRooms");
	}
}
