package com.meetingroom.mcpdemo;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link RoomCatalogTools} as tools of the MCP server (auto-config picks up every ToolCallbackProvider). */
@Configuration
public class McpDemoConfig {

	@Bean
	public ToolCallbackProvider roomTools(RoomCatalogTools tools) {
		return MethodToolCallbackProvider.builder().toolObjects(tools).build();
	}
}
